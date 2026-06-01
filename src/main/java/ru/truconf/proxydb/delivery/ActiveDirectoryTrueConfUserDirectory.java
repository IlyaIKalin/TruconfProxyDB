package ru.truconf.proxydb.delivery;

import java.util.Hashtable;
import java.util.Objects;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.springframework.stereotype.Service;
import ru.truconf.proxydb.config.AppProperties;
import ru.truconf.proxydb.truconf.TrueConfException;

@Service
public class ActiveDirectoryTrueConfUserDirectory implements TrueConfUserDirectory {

  private final AppProperties properties;

  public ActiveDirectoryTrueConfUserDirectory(AppProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  @Override
  public java.util.Optional<Entry> findByEmail(String email) {
    AppProperties.ActiveDirectory ad = properties.activeDirectory();
    if (!ad.enabled()) {
      return java.util.Optional.empty();
    }

    try {
      SearchResult result = search(ad, filter(ad.emailAttribute(), email));
      if (result == null) {
        return java.util.Optional.empty();
      }

      Attributes attrs = result.getAttributes();
      String trueconfId = attribute(attrs, ad.trueconfIdAttribute());
      if (trueconfId == null || trueconfId.isBlank()) {
        return java.util.Optional.empty();
      }

      String displayName = attribute(attrs, ad.displayNameAttribute());
      return java.util.Optional.of(new Entry(email, trueconfId, displayName));
    } catch (TrueConfException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new TrueConfException(
          "AD_LOOKUP_FAILED",
          "Active Directory lookup failed",
          true,
          ex);
    }
  }

  private SearchResult search(AppProperties.ActiveDirectory ad, String filter) throws Exception {
    Hashtable<String, String> env = new Hashtable<>();
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
    env.put(Context.PROVIDER_URL, ad.url());
    env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(ad.connectTimeout().toMillis()));
    env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(ad.readTimeout().toMillis()));
    if (hasText(ad.bindDn())) {
      env.put(Context.SECURITY_AUTHENTICATION, "simple");
      env.put(Context.SECURITY_PRINCIPAL, ad.bindDn());
      env.put(Context.SECURITY_CREDENTIALS, ad.bindPassword() == null ? "" : ad.bindPassword());
    } else {
      env.put(Context.SECURITY_AUTHENTICATION, "none");
    }

    InitialDirContext context = new InitialDirContext(env);
    try {
      SearchControls controls = new SearchControls();
      controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
      controls.setReturningAttributes(new String[] {
          ad.emailAttribute(),
          ad.trueconfIdAttribute(),
          ad.displayNameAttribute()
      });
      controls.setCountLimit(1);
      NamingEnumeration<SearchResult> results = context.search(ad.baseDn(), filter, controls);
      return results.hasMore() ? results.next() : null;
    } finally {
      context.close();
    }
  }

  private static String filter(String attributeName, String value) {
    return "(" + attributeName + "=" + escapeFilter(value) + ")";
  }

  private static String attribute(Attributes attrs, String name) throws Exception {
    var attr = attrs.get(name);
    if (attr == null) {
      return null;
    }
    Object value = attr.get();
    return value == null ? null : value.toString();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String escapeFilter(String value) {
    return value
        .replace("\\", "\\5c")
        .replace("*", "\\2a")
        .replace("(", "\\28")
        .replace(")", "\\29")
        .replace("\0", "\\00");
  }
}
