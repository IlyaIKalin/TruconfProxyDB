package ru.truconf.proxydb.truconf;

import java.util.function.Function;
import tools.jackson.databind.node.ObjectNode;

public interface TrueConfCommandTransport {

  TrueConfResponse request(Function<Long, ObjectNode> commandBuilder);
}
