package com.praxedo.securefiles.application.common.port.out;

import java.util.function.Supplier;

/**
 * A unit of work that commits entirely or not at all.
 *
 * <p>It is a port because the application layer may not import a framework,
 * and therefore cannot annotate itself {@code @Transactional}. The benefit is
 * that the transaction boundary is <em>visible</em> in the use case — one call,
 * one block — instead of being implied by an annotation on some method.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
