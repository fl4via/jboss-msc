/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.msc.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jboss.msc.txn.TaskBuilder;
import org.jboss.msc.txn.TaskController;
import org.jboss.msc.txn.Transaction;

/**
 * A service builder.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 */
final class ServiceBuilderImpl<T> implements ServiceBuilder<T> {
    private final ServiceContainer container;
    private final ServiceName name;
    private final Set<ServiceName> aliases = new HashSet<ServiceName>(0);
    private final Service<T> service;
    private final Map<ServiceName, DependencySpec<?>> specs = new LinkedHashMap<ServiceName, DependencySpec<?>>();
    private final boolean replacement;
    private final Transaction transaction;
    private ServiceMode mode;
    private TaskController<ServiceController<T>> installTask;
    private DependencySpec<?> parentDependencySpec;
    private final Set<TaskController<?>> taskDependencies = new HashSet<TaskController<?>>(0);

    public ServiceBuilderImpl(final ServiceContainer container, final Transaction transaction, final ServiceName name, final Service<T> service) {
        this(container, transaction, name, service, false);
    }

    public ServiceBuilderImpl(final ServiceContainer container, final Transaction transaction, final ServiceName name, final Service<T> service, final boolean replaceService) {
        this.transaction = transaction;
        this.container = container;
        this.name = name;
        this.service = service;
        this.replacement = true;
    }

    /**
     * Set the service mode.
     *
     * @param mode the service mode
     */
    public ServiceBuilder<T> setMode(final ServiceMode mode) {
        checkAlreadyInstalled();
        if (mode == null) {
            throw new IllegalArgumentException("mode is null");
        }
        this.mode = mode;
        return this;
    }

    /**
     * Add aliases for this service.
     *
     * @param aliases the service names to use as aliases
     * @return the builder
     */
    public ServiceBuilderImpl<T> addAliases(ServiceName... aliases) {
        checkAlreadyInstalled();
        if (aliases != null) for(ServiceName alias : aliases) {
            if(alias != null && !alias.equals(name)) {
                this.aliases.add(alias);
            }
        }
        return this;
    }

    public void install() throws IllegalStateException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceName name) {
        addDependency(container, name, DependencyFlag.NONE);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceName name, DependencyFlag... flags) {
        return addDependency(container, name, flags);
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceName name, Injector<?> injector) throws IllegalStateException {
        return addDependency(name, injector, DependencyFlag.NONE);
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceName name, Injector<?> injector, DependencyFlag... flags) {
        checkAlreadyInstalled();
        DependencySpec<Object> spec = new DependencySpec<Object>(container, name, flags);
        //spec.addInjection(injector);
        addDependencySpec(spec, name, flags);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceContainer container, ServiceName name) {
        return addDependency(container, name, DependencyFlag.NONE);
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceContainer container, ServiceName name, DependencyFlag... flags) {
        checkAlreadyInstalled();
        addDependencySpec(new DependencySpec(container, name, flags), name, flags);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceContainer container, ServiceName name, Injector<?> injector) {
        checkAlreadyInstalled();
        addDependency(container, name, injector, DependencyFlag.NONE);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(ServiceContainer container, ServiceName name, Injector<?> injector, DependencyFlag... flags) {
        checkAlreadyInstalled();
        final DependencySpec<?> spec = new DependencySpec<Object>(container, name, flags);
        //spec.addInjection(injector);
        addDependencySpec(spec, name, flags);
        return this;
    }

    private void addDependencySpec(DependencySpec<?> dependencySpec, ServiceName name, DependencyFlag... flags) {
        for (DependencyFlag flag: flags) {
            if (flag == DependencyFlag.PARENT) {
                synchronized (this) {
                    if (parentDependencySpec != null) {
                        throw new IllegalStateException("Service cannot have more than one parent dependency");
                    }
                    parentDependencySpec = dependencySpec;
                    specs.remove(name);
                    return;
                }
            }
        }
        if (name == parentDependencySpec.getName()) {
            parentDependencySpec = null;
        }
        specs.put(name, dependencySpec);
    }

    /**
     * {@inheritDoc}
     */
    public ServiceBuilder<T> addDependency(TaskController<?> task) {
        checkAlreadyInstalled();
        taskDependencies.add(task);
        return this;
    }

    /**
     * Initiate installation of this service as configured.  If the service was already installed, this method has no
     * effect.
     */
    public synchronized void build() {
        checkAlreadyInstalled();
        final TaskBuilder<ServiceController<T>> taskBuilder = transaction.newTask(new ServiceInstallTask<T>(transaction, this));
        taskBuilder.addDependencies(taskDependencies);
        if (replacement) {
            startReplacement(transaction, taskBuilder);
        }
        installTask = taskBuilder.release();
    }

    /**
     * Manually rollback this service installation.  If the service was already installed, it will be removed.
     */
    public void remove(Transaction transaction) {
        // TODO review this method
        final ServiceController<?> serviceController;
        synchronized (this) {
            serviceController = installTask.getResult();
        }
        if (serviceController != null) {
            serviceController.remove(transaction);
        }
    }

    private synchronized void checkAlreadyInstalled() {
        if (installTask != null) {
            throw new IllegalStateException("ServiceBuilder installation already requested.");
        }
    }

    /**
     * Perform installation of this service builder into registry.
     * 
     * @param transaction active transaction
     * @return            the installed service controller. May be {@code null} if the service is being created with a
     *                    parent dependency.
     */
    ServiceController<T> performInstallation(Transaction transaction) {
        if (parentDependencySpec != null) {
            parentDependencySpec.createDependency(transaction, this);
            return null;
        }
        return installController(transaction, null);
    }

    /**
     * Concludes service installation by creating and installing the service controller into the registry.
     * 
     * @param transaction      active transaction
     * @param parentDependency parent dependency, if any
     * @return the installed service controller
     */
    ServiceController<T> installController(Transaction transaction, Dependency<?> parentDependency) {
        final Registration registration = container.getRegistry().getOrCreateRegistration(transaction, name);
        final ServiceName[] aliasArray = aliases.toArray(new ServiceName[aliases.size()]);
        final Registration[] aliasRegistrations = new Registration[aliasArray.length];
        int i = 0; 
        for (ServiceName alias: aliases) {
            aliasRegistrations[i++] = container.getRegistry().getOrCreateRegistration(transaction, alias);
        }
        i = 0;
        final Dependency<?>[] dependencies;
        if (parentDependency == null) {
            dependencies = new Dependency<?>[specs.size()];
        } else {
            dependencies = new Dependency<?>[specs.size() + 1];
            dependencies[i++] = parentDependency;
        }
        for (DependencySpec<?> spec : specs.values()) {
            dependencies[i++] = spec.createDependency(transaction, this);
        }
        final ServiceController<T> serviceController =  new ServiceController<T>(transaction, dependencies, aliasRegistrations, registration, mode);
        serviceController.getValue().set(service);
        serviceController.install(transaction);
        if (replacement) {
            concludeReplacement(transaction, serviceController);
        }
        return serviceController;
    }

    private void startReplacement(Transaction transaction, TaskBuilder<ServiceController<T>> serviceInstallTaskBuilder) {
        startReplacement(transaction, container.getRegistry().getOrCreateRegistration(transaction, name), serviceInstallTaskBuilder);
        for (ServiceName alias: aliases) {
            startReplacement(transaction, container.getRegistry().getOrCreateRegistration(transaction, alias), serviceInstallTaskBuilder);
        }
    }

    private void startReplacement(Transaction transaction, Registration registration, TaskBuilder<ServiceController<T>> serviceInstallTaskBuilder) {
        for (Dependency<?> dependency: registration.getIncomingDependencies()) {
            dependency.dependencyReplacementStarted(transaction);
        }
        ServiceController<?> serviceController = registration.getController();
        if (serviceController != null) {
            serviceInstallTaskBuilder.addDependency(serviceController.remove(transaction));
        }
    }

    private void concludeReplacement(Transaction transaction, ServiceController<?> serviceController) {
        concludeReplacement(transaction, serviceController.getPrimaryRegistration());
        for (Registration registration: serviceController.getAliasRegistrations()) {
            concludeReplacement(transaction,  registration);
        }
    }

    private void concludeReplacement(Transaction transaction, Registration registration) {
        for (Dependency<?> dependency: registration.getIncomingDependencies()) {
            dependency.dependencyReplacementConcluded(transaction);
        }
    }
}
