package com.assessment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import static org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation.DECIMAL128;

@Configuration
public class MongoNumbers {
    @Bean MongoCustomConversions decimalMarks() {
        return MongoCustomConversions.create(adapter -> adapter.bigDecimal(DECIMAL128));
    }
}
