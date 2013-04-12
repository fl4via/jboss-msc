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

import org.jboss.msc.txn.Committable;
import org.jboss.msc.txn.ExecuteContext;
import org.jboss.msc.txn.Revertible;
import org.jboss.msc.txn.Validatable;

/**
 * A service which starts and stops .  Services may be stopped and started multiple times.  A service may additionally
 * implement {@link Validatable}, {@link Revertible}, and/or {@link Committable} to implement full transactional
 * start semantics.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public interface Service<T> {

    /**
     * Start the service.  If this method throws an exception, it will be recorded as a failure to start.  Because
     * this method executes transactionally, another service that has the same name or is competing for the same resource
     * may already be installed.  For this reason, any potentially conflicting actions should occur during the commit
     * phase.
     *
     * @param startContext the start context
     */
    void start(ExecuteContext<T> startContext);

    /**
     * TODO discuss this change
     * Commit the service stop.  Expected to succeed; any exceptions thrown will be ignored.
     */
    void stop(ExecuteContext<Void> context);
}
