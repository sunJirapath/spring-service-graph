package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.List;
import java.util.Set;

/** One concern (inbound HTTP, Kafka, ...). Extractors never depend on each other. */
public interface Extractor {

    List<Fact> extract(Service service);

    static List<Extractor> all() {
        return all(Set.of());
    }

    /** @param sqlMethods in-house wrapper methods that take SQL, for {@link JdbcExtractor} */
    static List<Extractor> all(Set<String> sqlMethods) {
        return List.of(new InboundHttpExtractor(), new OutboundHttpExtractor(), new KafkaExtractor(),
                new DataExtractor(), new GatewayExtractor(), new JdbcExtractor(sqlMethods));
    }
}
