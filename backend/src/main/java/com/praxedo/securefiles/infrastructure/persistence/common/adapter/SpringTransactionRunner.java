package com.praxedo.securefiles.infrastructure.persistence.common.adapter;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.praxedo.securefiles.application.common.port.out.TransactionRunner;

/**
 * The transaction port, on Spring's transaction manager.
 *
 * <p>With JPA on the classpath the manager is a {@code JpaTransactionManager},
 * which exposes its JDBC connection: a {@code JdbcClient} statement run inside
 * {@link #inTransaction} joins the same transaction as the entity writes. What
 * it does <em>not</em> do is flush Hibernate first — so a use case that writes
 * an entity and then a row referencing it must flush in between. The catalogue
 * does exactly that on insert.
 */
@Component
class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    SpringTransactionRunner(PlatformTransactionManager transactions) {
        this.template = new TransactionTemplate(transactions);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
