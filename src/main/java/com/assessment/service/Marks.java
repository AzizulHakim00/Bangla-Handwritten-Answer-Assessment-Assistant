package com.assessment.service;

import java.math.BigDecimal;
import java.util.Map;
import org.bson.types.Decimal128;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Marks {
    private Marks() {}

    public static BigDecimal decimal(Object value) {
        if (value instanceof Decimal128 number) return number.bigDecimalValue();
        if (value instanceof BigDecimal number) return number;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        throw new IllegalArgumentException("Missing score");
    }

    public static BigDecimal validate(BigDecimal value, BigDecimal maximum, String message) {
        if (value == null || value.scale() > 2 || value.signum() < 0 || value.compareTo(maximum) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        var normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    public static BigDecimal value(Map<?, ?> item, String key) { return decimal(item.get(key)); }
}
