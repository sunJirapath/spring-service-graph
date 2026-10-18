package io.github.sunjirapath.ssg.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/** A graph node. The {@code id} is globally unique and stable across runs. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(Node.Service.class), @JsonSubTypes.Type(Node.Endpoint.class),
        @JsonSubTypes.Type(Node.Topic.class), @JsonSubTypes.Type(Node.Table.class),
        @JsonSubTypes.Type(Node.External.class)})
@JsonPropertyOrder({"id"})
public sealed interface Node {

    String id();

    @JsonTypeName("service")
    record Service(String id, String name, String repo, String path) implements Node {
        public static String idOf(String name) { return "service:" + name; }
    }

    @JsonTypeName("endpoint")
    record Endpoint(String id, String service, String method, String path) implements Node {
        public static String idOf(String service, String method, String path) {
            return "endpoint:" + service + ":" + method + " " + path;
        }
    }

    @JsonTypeName("topic")
    record Topic(String id, String name) implements Node {
        public static String idOf(String name) { return "topic:" + name; }
    }

    /** Tables are scoped by service: static scanning cannot tell whether two services share a database. */
    @JsonTypeName("table")
    record Table(String id, String service, String name, String store) implements Node {
        public static String idOf(String service, String name) { return "table:" + service + "/" + name; }
    }

    @JsonTypeName("external")
    record External(String id, String name) implements Node {
        public static String idOf(String name) { return "external:" + name; }
    }
}
