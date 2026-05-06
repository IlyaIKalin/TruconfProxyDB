package ru.truconf.proxydb.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.truconf.proxydb.config.AppProperties;

@Component
@ConditionalOnProperty(prefix = "truconf.dispatcher", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class PostgresNotifyListener {

  static final String OUTBOX_CHANNEL = "truconf_outbox_new";

  private static final Logger log = LoggerFactory.getLogger(PostgresNotifyListener.class);
  private static final int NOTIFICATION_TIMEOUT_MILLIS = 1_000;

  private final DataSource dataSource;
  private final Duration reconnectDelay;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean listening = new AtomicBoolean();

  private volatile Thread listenerThread;
  private volatile Connection connection;

  @Autowired
  public PostgresNotifyListener(DataSource dataSource, AppProperties properties) {
    this(dataSource, properties.dispatcher().pollInterval());
  }

  PostgresNotifyListener(DataSource dataSource, Duration reconnectDelay) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.reconnectDelay = positiveDelay(reconnectDelay);
  }

  public void start(Runnable notificationCallback) {
    Objects.requireNonNull(notificationCallback, "notificationCallback must not be null");
    if (!running.compareAndSet(false, true)) {
      return;
    }

    Thread thread = new Thread(
        () -> listenLoop(notificationCallback),
        "outbox-postgres-listener");
    thread.setDaemon(false);
    listenerThread = thread;
    thread.start();
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    closeConnection();
    Thread thread = listenerThread;
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join(NOTIFICATION_TIMEOUT_MILLIS + reconnectDelay.toMillis());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    }
    listening.set(false);
  }

  public boolean isRunning() {
    return running.get();
  }

  public boolean isListening() {
    return listening.get();
  }

  private void listenLoop(Runnable notificationCallback) {
    while (running.get()) {
      try {
        listenOnConnection(notificationCallback);
      } catch (SQLException ex) {
        listening.set(false);
        if (running.get()) {
          log.warn("PostgreSQL LISTEN connection failed; reconnect will be attempted", ex);
          sleepBeforeReconnect();
        }
      } finally {
        closeConnection();
        listening.set(false);
      }
    }
  }

  private void listenOnConnection(Runnable notificationCallback) throws SQLException {
    try (Connection currentConnection = dataSource.getConnection()) {
      connection = currentConnection;
      currentConnection.setAutoCommit(true);
      PGConnection pgConnection = currentConnection.unwrap(PGConnection.class);
      try (Statement statement = currentConnection.createStatement()) {
        statement.execute("listen " + OUTBOX_CHANNEL);
      }
      listening.set(true);
      log.info("Listening for PostgreSQL notifications on channel {}", OUTBOX_CHANNEL);

      while (running.get()) {
        PGNotification[] notifications =
            pgConnection.getNotifications(NOTIFICATION_TIMEOUT_MILLIS);
        if (notifications == null || notifications.length == 0) {
          continue;
        }
        for (PGNotification notification : notifications) {
          if (OUTBOX_CHANNEL.equals(notification.getName())) {
            notificationCallback.run();
          }
        }
      }
    } finally {
      connection = null;
    }
  }

  private void closeConnection() {
    Connection currentConnection = connection;
    if (currentConnection == null) {
      return;
    }
    try {
      currentConnection.close();
    } catch (SQLException ex) {
      log.debug("Failed to close PostgreSQL LISTEN connection", ex);
    }
  }

  private void sleepBeforeReconnect() {
    try {
      Thread.sleep(reconnectDelay.toMillis());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static Duration positiveDelay(Duration delay) {
    if (delay == null || delay.isNegative() || delay.isZero()) {
      return Duration.ofSeconds(1);
    }
    return delay;
  }
}
