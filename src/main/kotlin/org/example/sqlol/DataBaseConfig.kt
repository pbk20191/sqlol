package org.example.sqlol

//import io.r2dbc.spi.ConnectionFactory
//import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration
//import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
//import org.springframework.data.relational.core.mapping.Table
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.wiring.BeanConfigurerSupport
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer
//import org.springframework.boot.jdbc.autoconfigure.DataSourceConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.embedded.ConnectionProperties
import org.springframework.jdbc.datasource.embedded.DataSourceFactory
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseConfigurer
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.util.StringUtils
import java.sql.Driver
import javax.sql.DataSource

@Configuration
@EnableJpaRepositories
@EntityScan(basePackages = ["org.example.sqlol.models"])
//@EnableR2dbcRepositories
class DataBaseConfig {


    fun asdf() {
        runBlocking {


        }
    }

//    @Bean
//
//    fun dataSourceFooooooooo(
//        virtualExecutor: LoomScheduledExecutorService,
//    ) = object: BeanPostProcessor {
//        override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any? {
//            if (bean is HikariDataSource) {
////                bean.threadFactory = SqlolApplication.VFactory()
//                bean.scheduledExecutor = virtualExecutor
//
//            }
//            return bean
//        }
//    }
}

