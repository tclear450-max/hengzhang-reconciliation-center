package com.hengzhang;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfig {
    /** Preserve all BIGINT monetary values when consumed by a JavaScript client. */
    @Bean SimpleModule exactIntegers() {
        var module=new SimpleModule();
        module.addSerializer(Long.class,ToStringSerializer.instance);
        module.addSerializer(Long.TYPE,ToStringSerializer.instance);
        module.addSerializer(java.math.BigDecimal.class,ToStringSerializer.instance);
        module.addSerializer(java.math.BigInteger.class,ToStringSerializer.instance);
        return module;
    }
}
