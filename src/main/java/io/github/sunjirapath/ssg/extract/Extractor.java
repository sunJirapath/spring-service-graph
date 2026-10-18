package io.github.sunjirapath.ssg.extract;

import io.github.sunjirapath.ssg.model.Fact;
import io.github.sunjirapath.ssg.scan.Service;

import java.util.List;

/** One concern (inbound HTTP, Kafka, ...). Extractors never depend on each other. */
public interface Extractor {

    List<Fact> extract(Service service);

    static List<Extractor> all() {
        return List.of(new InboundHttpExtractor(), new OutboundHttpExtractor(), new KafkaExtractor(),
                new DataExtractor(), new GatewayExtractor());
    }
}
