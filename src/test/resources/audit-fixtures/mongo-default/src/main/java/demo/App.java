package demo;
@SpringBootApplication class App {}
class Customer { @Id String id; }
interface CustomerRepository extends MongoRepository<Customer, String> {}
