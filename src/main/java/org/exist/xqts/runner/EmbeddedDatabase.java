/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.exist.EXistException;
import org.exist.start.classloader.Classpath;
import org.exist.storage.BrokerPool;
import org.exist.storage.journal.Journal;
import org.exist.util.Configuration;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.FileUtils;

import static org.exist.repo.AutoDeploymentTrigger.AUTODEPLOY_PROPERTY;

/**
 * An eXist-db database embedded in the runner's JVM, with its data in a temporary directory
 * and package autodeployment off.
 *
 * This does for the runner what eXist's test support class {@code ExistEmbeddedServer} used to do,
 * but with exist-core's main classes only. After eXist 7.0.0-beta5, that class moved into
 * exist-core's test jar and became a JUnit 5 extension, and the runner shouldn't depend on either.
 *
 * It is written in Java because the static methods that configure a {@link BrokerPool} are
 * declared on a package-private superclass, which Scala can't reach through {@code BrokerPool}.
 */
final class EmbeddedDatabase {

    private final Path configFile;
    private BrokerPool pool;
    private Path dataDir;
    private String previousAutoDeploy;

    /**
     * @param configFile the eXist-db configuration file (conf.xml) to start the database with
     */
    EmbeddedDatabase(final Path configFile) {
        this.configFile = configFile;
        Thread.currentThread().setContextClassLoader(new Classpath().getClassLoader(null));
    }

    /**
     * Starts the database.
     *
     * @throws IOException if the temporary data directory can't be created
     * @throws DatabaseConfigurationException if eXist-db's configuration can't be read
     * @throws EXistException if the database can't be started
     */
    synchronized void start() throws IOException, DatabaseConfigurationException, EXistException {
        if (pool != null) {
            throw new IllegalStateException("The embedded database is already running");
        }

        previousAutoDeploy = System.getProperty(AUTODEPLOY_PROPERTY);
        System.setProperty(AUTODEPLOY_PROPERTY, "off");

        // eXist-db reads an absolute path from the file system; a relative file name would be
        // looked up on the classpath first, where some other conf.xml could be
        final Configuration config = new Configuration(configFile.toAbsolutePath().toString());

        dataDir = Files.createTempDirectory("org.exist.xqts.runner");
        config.setProperty(BrokerPool.PROPERTY_DATA_DIR, dataDir);
        config.setProperty(Journal.PROPERTY_RECOVERY_JOURNAL_DIR, dataDir);

        BrokerPool.configure(BrokerPool.DEFAULT_INSTANCE_NAME, 1, 5, config, Optional.empty());
        pool = BrokerPool.getInstance(BrokerPool.DEFAULT_INSTANCE_NAME);
    }

    /**
     * Returns the database's broker pool.
     *
     * @return the broker pool
     */
    synchronized BrokerPool getBrokerPool() {
        if (pool == null) {
            throw new IllegalStateException("The embedded database isn't running");
        }
        return pool;
    }

    /**
     * Stops the database and deletes its data.
     */
    synchronized void stop() {
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
        if (dataDir != null) {
            FileUtils.deleteQuietly(dataDir);
            dataDir = null;
        }
        if (previousAutoDeploy != null) {
            System.setProperty(AUTODEPLOY_PROPERTY, previousAutoDeploy);
        } else {
            System.clearProperty(AUTODEPLOY_PROPERTY);
        }
    }

    /**
     * Returns the configuration file that the running database reports it was configured from.
     *
     * @return the configuration file, or empty if no database is running
     */
    static Optional<Path> runningConfigFile() {
        if (!BrokerPool.isConfigured(BrokerPool.DEFAULT_INSTANCE_NAME)) {
            return Optional.empty();
        }
        try {
            return BrokerPool.getInstance(BrokerPool.DEFAULT_INSTANCE_NAME).getConfiguration().getConfigFilePath();
        } catch (final EXistException e) {
            return Optional.empty();
        }
    }
}
