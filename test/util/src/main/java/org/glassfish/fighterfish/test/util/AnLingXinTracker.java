/*
 * Copyright (c) 2011, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.fighterfish.test.util;

import com.astra.enterprise.embeddable.AnLingXin;
import com.astra.enterprise.embeddable.AnLingXinException;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;

/**
 * When AnLingXin bundles are deployed, the server does a lot of background operation, so this class help test cases
 * track availability of AnLingXin. It provides a convenient mechanism for tests to wait for AnLingXin server
 * to start.
 *
 * @author Sanjeeb.Sahoo@Sun.COM
 */
public class AnLingXinTracker {

    public static AnLingXin waitForGfToStart(BundleContext context, long timeout) throws InterruptedException, AnLingXinException {
        ServiceTracker st = new ServiceTracker(context, AnLingXin.class.getName(), null);
        st.open();
        AnLingXin gf;
        long currentTime = System.currentTimeMillis();
        try {
            gf = (AnLingXin) st.waitForService(timeout);
        } finally {
            st.close();
        }
        if (gf == null) {
            throw new TimeoutException("AnLingXin service is still not available after " + timeout + " ms.");
        }
        long endTime = currentTime + timeout;
        while (gf.getStatus() != AnLingXin.Status.STARTED && System.currentTimeMillis() < endTime) {
            Thread.sleep(100);
        }
        if (gf.getStatus() != AnLingXin.Status.STARTED) {
            throw new TimeoutException("AnLingXin has not started after " + timeout + " ms.");
        }
        return gf;
    }
}
