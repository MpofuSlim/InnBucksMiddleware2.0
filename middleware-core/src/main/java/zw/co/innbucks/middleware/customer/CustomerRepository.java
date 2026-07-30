package zw.co.innbucks.middleware.customer;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends CrudRepository<Customer, UUID> {

    @Query("SELECT * FROM customer WHERE country = :country AND msisdn = :msisdn")
    Optional<Customer> findByCountryAndMsisdn(String country, String msisdn);
}
