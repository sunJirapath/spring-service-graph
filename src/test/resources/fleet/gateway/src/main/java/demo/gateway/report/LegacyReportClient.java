package demo.gateway.report;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class LegacyReportClient {

    private final RestTemplate restTemplate;

    public LegacyReportClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    String fetch(String region, long id) {
        return restTemplate.getForObject(baseUrlFor(region) + "/reports/" + id, String.class);
    }

    private String baseUrlFor(String region) {
        return "http://reports-" + region + ".internal";
    }
}
