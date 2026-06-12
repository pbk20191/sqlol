package org.example.demo1webfl.config

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component
import java.lang.reflect.Constructor
import java.util.concurrent.ThreadFactory

@Component
@ConfigurationPropertiesBinding
class StringToThreadFactoryConverter : Converter<String, ThreadFactory?> {
    override fun convert(source: String): ThreadFactory? {
        if (source.isBlank()) {
            return null
        }

        try {
            val clazz = Class.forName(source)

            require(ThreadFactory::class.java.isAssignableFrom(clazz)) { "Class does not implement ThreadFactory: " + source }

            val threadFactoryClass =
                clazz as Class<out ThreadFactory?>

            val constructor: Constructor<out ThreadFactory?> =
                threadFactoryClass.getDeclaredConstructor()
            constructor.setAccessible(true)

            return constructor.newInstance()
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to create ThreadFactory from class name: " + source, e
            )
        }
    }
}