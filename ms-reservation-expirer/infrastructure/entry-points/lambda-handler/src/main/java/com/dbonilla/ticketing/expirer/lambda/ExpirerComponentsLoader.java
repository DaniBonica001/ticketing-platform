package com.dbonilla.ticketing.expirer.lambda;

import java.util.ServiceLoader;

/**
 * Finds the registered {@link ExpirerComponentsProvider} and builds the components.
 */
final class ExpirerComponentsLoader {

    private ExpirerComponentsLoader() {
    }

    static ExpirerComponents load() {
        return load(ServiceLoader.load(ExpirerComponentsProvider.class, ExpirerComponentsLoader.class.getClassLoader()));
    }

    static ExpirerComponents load(Iterable<ExpirerComponentsProvider> providers) {
        var iterator = providers.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("No " + ExpirerComponentsProvider.class.getName() + " is registered");
        }
        return iterator.next().create();
    }
}
