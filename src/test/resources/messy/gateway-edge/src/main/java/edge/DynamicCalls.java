package edge;

class DynamicCalls {
    WebClient webClient;
    String legacyHost = "http://legacy-api";

    Object resolvedDespiteMutableField() {
        return webClient.get().uri(legacyHost + "/legacy/orders/42").retrieve();
    }

    void reassignedLater() {
        legacyHost = "http://changed-at-runtime.invalid";
    }

    Object unresolved(String tenant, long id) {
        return webClient.get().uri(runtimeBase(tenant) + "/runtime/" + id).retrieve();
    }

    String runtimeBase(String tenant) { return "https://" + tenant; }
}
