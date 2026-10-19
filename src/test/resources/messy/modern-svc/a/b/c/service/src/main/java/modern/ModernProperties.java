package modern;

@ConfigurationProperties(prefix = "modern")
public record ModernProperties(String region, int retries) {}
