package ru.truconf.proxydb.truconf;

import tools.jackson.databind.JsonNode;

public record TrueConfError(
    String code,
    String message,
    JsonNode rawResponse) {
}
