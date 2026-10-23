package demo;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
@SpringBootApplication class App {}
@Document(indexName = "catalog") class Product {}
interface ProductRepository extends ElasticsearchRepository<Product, String> {}
