package com.dbonilla.ticketing.expirer.lambda;

/**
 * Implemented by the composition root (app-service) and discovered with {@link java.util.ServiceLoader},
 * so this entry point never depends on the driven adapters.
 */
public interface ExpirerComponentsProvider {

    ExpirerComponents create();
}
