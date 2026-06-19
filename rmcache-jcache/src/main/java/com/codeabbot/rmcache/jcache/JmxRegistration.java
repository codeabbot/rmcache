/*
 * Copyright 2026 Rabindra Meher
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
package com.codeabbot.rmcache.jcache;

import javax.cache.CacheException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Registers RMCache's JCache MBeans under the JSR-107 standard object names:
 * {@code javax.cache:type=CacheStatistics|CacheConfiguration,CacheManager=<uri>,Cache=<name>}.
 */
final class JmxRegistration {

    enum Type {
        CONFIGURATION("CacheConfiguration"),
        STATISTICS("CacheStatistics");

        private final String value;

        Type(String value) {
            this.value = value;
        }
    }

    private JmxRegistration() {
    }

    static ObjectName objectName(String cacheManagerUri, String cacheName, Type type) {
        try {
            return new ObjectName("javax.cache:type=" + type.value
                    + ",CacheManager=" + sanitize(cacheManagerUri)
                    + ",Cache=" + sanitize(cacheName));
        } catch (MalformedObjectNameException e) {
            throw new CacheException("Could not build ObjectName for cache '" + cacheName + "'", e);
        }
    }

    static void register(ObjectName name, Object mbean) {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        if (!server.isRegistered(name)) {
            try {
                server.registerMBean(mbean, name);
            } catch (Exception e) {
                throw new CacheException("Could not register MBean " + name, e);
            }
        }
    }

    static void unregister(ObjectName name) {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        if (server.isRegistered(name)) {
            try {
                server.unregisterMBean(name);
            } catch (Exception e) {
                throw new CacheException("Could not unregister MBean " + name, e);
            }
        }
    }

    /** ObjectName values may not contain the reserved characters {@code , : = " * ? \n}. */
    private static String sanitize(String value) {
        return String.valueOf(value).replaceAll("[,:=\"*?\\n]", ".");
    }
}
