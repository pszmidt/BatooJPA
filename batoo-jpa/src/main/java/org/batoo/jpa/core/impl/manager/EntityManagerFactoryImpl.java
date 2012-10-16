/*
 * Copyright (c) 2012 - Batoo Software ve Consultancy Ltd.
 * 
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.batoo.jpa.core.impl.manager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceException;
import javax.persistence.PersistenceUnitUtil;
import javax.persistence.Query;
import javax.sql.DataSource;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import javax.validation.groups.Default;

import org.apache.commons.lang.StringUtils;
import org.batoo.jpa.common.BatooException;
import org.batoo.jpa.common.log.BLogger;
import org.batoo.jpa.common.log.BLoggerFactory;
import org.batoo.jpa.core.BJPASettings;
import org.batoo.jpa.core.JPASettings;
import org.batoo.jpa.core.impl.cache.CacheImpl;
import org.batoo.jpa.core.impl.criteria.CriteriaBuilderImpl;
import org.batoo.jpa.core.impl.criteria.QueryImpl;
import org.batoo.jpa.core.impl.criteria.jpql.JpqlQuery;
import org.batoo.jpa.core.impl.deployment.DdlManager;
import org.batoo.jpa.core.impl.deployment.LinkManager;
import org.batoo.jpa.core.impl.deployment.NamedQueriesManager;
import org.batoo.jpa.core.impl.jdbc.AbstractJdbcAdaptor;
import org.batoo.jpa.core.impl.jdbc.BoneCPDataSource;
import org.batoo.jpa.core.impl.jdbc.PreparedStatementProxy.SqlLoggingType;
import org.batoo.jpa.core.impl.model.MetamodelImpl;
import org.batoo.jpa.core.jdbc.DDLMode;
import org.batoo.jpa.core.jdbc.adapter.JdbcAdaptor;
import org.batoo.jpa.parser.PersistenceParser;
import org.batoo.jpa.parser.impl.AbstractLocator;
import org.batoo.jpa.parser.metadata.NamedQueryMetadata;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Implementation of {@link EntityManagerFactory}.
 * 
 * @author hceylan
 * @since $version
 */
public class EntityManagerFactoryImpl implements EntityManagerFactory {

	private static final BLogger LOG = BLoggerFactory.getLogger(EntityManagerFactoryImpl.class);

	private static final int NO_QUERIES_MAX = 1000;
	private static final int NO_QUERIES_TRIM = 100;

	private final MetamodelImpl metamodel;
	private final DDLMode ddlMode;

	private final DataSource dataSource;
	private final long slowSqlThreshold;
	private final SqlLoggingType sqlLogging;

	private final CacheImpl cache;

	private final JdbcAdaptor jdbcAdaptor;
	private final Map<String, Object> properties = Maps.newHashMap();
	private final Map<String, JpqlQuery> namedQueries = Maps.newHashMap();
	private final CriteriaBuilderImpl criteriaBuilder;
	private final PersistenceUnitUtilImpl persistenceUtil;

	private final HashMap<String, JpqlQuery> jpqlCache = Maps.newHashMap();
	private final ClassLoader classloader;

	private final ValidatorFactory validationFactory;
	private final Class<?>[] persistValidators;
	private final Class<?>[] updateValidators;
	private final Class<?>[] removeValidators;

	private boolean open;

	/**
	 * @param name
	 *            the name of the entity manager factory
	 * @param parser
	 *            the persistence parser
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public EntityManagerFactoryImpl(String name, PersistenceParser parser) {
		super();

		this.classloader = parser.getClassloader();
		this.prepareProperties(parser);

		final boolean hasValidators = parser.hasValidators();
		if (hasValidators) {
			this.validationFactory = Validation.buildDefaultValidatorFactory();

			this.persistValidators = this.getValidatorsFor(parser, JPASettings.PERSIST_VALIDATION_GROUP);
			this.updateValidators = this.getValidatorsFor(parser, JPASettings.UPDATE_VALIDATION_GROUP);
			this.removeValidators = this.getValidatorsFor(parser, JPASettings.REMOVE_VALIDATION_GROUP);
		}
		else {
			this.validationFactory = null;
			this.persistValidators = null;
			this.updateValidators = null;
			this.removeValidators = null;
		}

		this.dataSource = this.createDatasource(parser);

		try {
			this.slowSqlThreshold = this.getProperty(BJPASettings.SLOW_SQL_THRESHOLD) != null ? //
				Long.valueOf((String) this.getProperty(BJPASettings.SLOW_SQL_THRESHOLD)) : //
				BJPASettings.DEFAULT_SLOW_SQL_THRESHOLD;
		}
		catch (final Exception e) {
			throw new IllegalArgumentException("Illegal value " + this.getProperty(BJPASettings.SLOW_SQL_THRESHOLD) + " for " + BJPASettings.SLOW_SQL_THRESHOLD);
		}

		try {
			this.sqlLogging = this.getProperty(BJPASettings.SQL_LOGGING) != null ? //
				SqlLoggingType.valueOf(((String) this.getProperty(BJPASettings.SQL_LOGGING)).toUpperCase(Locale.ENGLISH)) : //
				SqlLoggingType.NONE;
		}
		catch (final Exception e) {
			throw new IllegalArgumentException("Illegal value " + this.getProperty(BJPASettings.SQL_LOGGING) + " for " + BJPASettings.SQL_LOGGING);
		}

		this.cache = new CacheImpl(this, parser.getSharedCacheMode());

		this.ddlMode = this.readDdlMode();

		this.jdbcAdaptor = this.createJdbcAdaptor();
		this.metamodel = new MetamodelImpl(this, this.jdbcAdaptor, parser.getMetadata());

		LinkManager.perform(this.metamodel);

		// drop all tables if ddl mode is drop
		if (this.ddlMode == DDLMode.DROP) {
			this.metamodel.dropAllTables(this.dataSource);
		}

		DdlManager.perform(this.dataSource, this.metamodel, this.ddlMode);

		this.metamodel.performSequencesDdl(this.dataSource, this.ddlMode);
		this.metamodel.performTableGeneratorsDdl(this.dataSource, this.ddlMode);

		this.metamodel.preFillGenerators(this.dataSource);
		this.criteriaBuilder = new CriteriaBuilderImpl(this.metamodel);

		NamedQueriesManager.perform(this.metamodel, this.criteriaBuilder);

		this.persistenceUtil = new PersistenceUnitUtilImpl(this);

		this.open = true;
	}

	/**
	 * Adds the named query to the entity manager factory.
	 * 
	 * @param name
	 *            the name of the query
	 * @param jpqlQuery
	 *            the compiled query
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public void addNamedQuery(String name, JpqlQuery jpqlQuery) {
		if (this.namedQueries.containsKey(name)) {
			throw new IllegalArgumentException("A named query with the same name already exists: " + name);
		}

		this.namedQueries.put(name, jpqlQuery);
	}

	/**
	 * Adds the query to the named queries.
	 * 
	 * @param name
	 *            the name fo the query
	 * @param query
	 *            the query
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public void addNamedQuery(final String name, Query query) {
		final QueryImpl<?> typedQuery = (QueryImpl<?>) query;
		final String jpql = typedQuery.getCriteriaQuery().getJpql();

		new JpqlQuery(this, this.criteriaBuilder, new NamedQueryMetadata() {

			@Override
			public Map<String, Object> getHints() {
				return typedQuery.getHints();
			}

			@Override
			public AbstractLocator getLocator() {
				return null;
			}

			@Override
			public LockModeType getLockMode() {
				return typedQuery.getLockMode();
			}

			@Override
			public String getName() {
				return name;
			}

			@Override
			public String getQuery() {
				return jpql;
			}
		});
	}

	/**
	 * Checks if the entity manager factory is open.
	 * 
	 * @since $version
	 * @author hceylan
	 */
	protected void assertOpen() {
		if (!this.open) {
			throw new IllegalStateException("EntityManagerFactory has been previously closed");
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public void close() {
		this.assertOpen();

		this.metamodel.stopIdGenerators();

		final String dropOnClose = (String) this.getProperty(BJPASettings.DROP_ON_CLOSE);
		if ("true".equals(dropOnClose)) {
			this.metamodel.dropAllTables(this.dataSource);
		}

		if (this.dataSource instanceof BoneCPDataSource) {
			((BoneCPDataSource) this.dataSource).close();
		}

		this.open = false;
	}

	private DataSource createDatasource(PersistenceParser parser) {
		try {
			if (StringUtils.isNotBlank(parser.getJtaDatasource())) {
				return (DataSource) new javax.naming.InitialContext().lookup(parser.getJtaDatasource());
			}

			if (StringUtils.isNotBlank(parser.getNonJtaDatasource())) {
				return (DataSource) new javax.naming.InitialContext().lookup(parser.getNonJtaDatasource());
			}
		}
		catch (final NamingException e) {
			throw new PersistenceException("Cannot lookup datasource: " + e.getMessage(), e);
		}

		try {
			// read the properties
			final String jdbcDriver = (String) this.getProperty(JPASettings.JDBC_DRIVER);
			final String jdbcUrl = (String) this.getProperty(JPASettings.JDBC_URL);
			final String jdbcUser = (String) this.getProperty(JPASettings.JDBC_USER);
			final String jdbcPassword = (String) this.getProperty(JPASettings.JDBC_PASSWORD);

			final Integer statementsCacheSize = this.getProperty(BJPASettings.STATEMENT_CACHE_SIZE) != null ? //
				Integer.valueOf((String) this.getProperty(BJPASettings.STATEMENT_CACHE_SIZE)) : //
				BJPASettings.DEFAULT_STATEMENT_CACHE_SIZE;

			final Integer maxConnections = this.getProperty(BJPASettings.MAX_CONNECTIONS) != null ? //
				Integer.valueOf((String) this.getProperty(BJPASettings.MAX_CONNECTIONS)) : //
				BJPASettings.DEFAULT_MAX_CONNECTIONS;

			final Integer minConnections = this.getProperty(BJPASettings.MIN_CONNECTIONS) != null ? //
				Integer.valueOf((String) this.getProperty(BJPASettings.MIN_CONNECTIONS)) : //
				BJPASettings.DEFAULT_MIN_CONNECTIONS;

			// create the datasource
			final BoneCPDataSource dataSource = new BoneCPDataSource();

			dataSource.setDriverClass(jdbcDriver);
			dataSource.setJdbcUrl(jdbcUrl);
			dataSource.setUsername(jdbcUser);
			dataSource.setPassword(jdbcPassword);

			// This is slow so always set it to 0
			dataSource.setReleaseHelperThreads(0);

			dataSource.setStatementsCacheSize(statementsCacheSize);
			dataSource.setMinConnectionsPerPartition(minConnections);
			dataSource.setMaxConnectionsPerPartition(maxConnections);

			return dataSource;
		}
		catch (final Exception e) {
			throw new IllegalArgumentException("Illegal values for datasource settings!");
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public EntityManagerImpl createEntityManager() {
		this.assertOpen();

		return new EntityManagerImpl(this, this.metamodel, this.dataSource, Collections.<String, Object> emptyMap(), this.jdbcAdaptor);
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public EntityManager createEntityManager(Map<String, Object> map) {
		this.assertOpen();

		return new EntityManagerImpl(this, this.metamodel, this.dataSource, map, this.jdbcAdaptor);
	}

	/**
	 * Creates the JDBC adaptor
	 * 
	 * @return the JDBC Adaptor
	 * 
	 * @since $version
	 * @author hceylan
	 */
	private JdbcAdaptor createJdbcAdaptor() {
		try {
			final Connection connection = this.dataSource.getConnection();
			try {
				return AbstractJdbcAdaptor.getAdapter(this.classloader, connection.getMetaData().getDatabaseProductName());
			}
			finally {
				connection.close();
			}
		}
		catch (final SQLException e) {
			throw new BatooException("Unable to get connection from the datasource", e);
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public CacheImpl getCache() {
		return this.cache;
	}

	/**
	 * Returns the classloader of the entity manager factory.
	 * 
	 * @return the classloader of the entity manager factory
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public ClassLoader getClassloader() {
		return this.classloader;
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public CriteriaBuilderImpl getCriteriaBuilder() {
		this.assertOpen();

		return this.criteriaBuilder;
	}

	/**
	 * Returns the datasource of the entity manager factory.
	 * 
	 * @return the datasource of the entity manager
	 * 
	 * @since $version
	 * @author hceylan
	 */
	protected DataSource getDatasource() {
		return this.dataSource;
	}

	/**
	 * Returns the JDBC Adaptor of the entity manager factory.
	 * 
	 * @return the JDBC Adaptor of the entity manager
	 * 
	 * @since $version
	 * @author hceylan
	 */
	protected JdbcAdaptor getJdbcAdaptor() {
		return this.jdbcAdaptor;
	}

	/**
	 * Returns a lazy created {@link JpqlQuery} for the query.
	 * 
	 * @param qlString
	 *            the JPQL query string
	 * @return the Jpql Query object
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public JpqlQuery getJpqlQuery(String qlString) {
		try {
			EntityManagerFactoryImpl.LOG.debug("JPQL: {0}", qlString);

			JpqlQuery jpqlQuery = this.jpqlCache.get(qlString);
			if (jpqlQuery == null) {
				jpqlQuery = new JpqlQuery(EntityManagerFactoryImpl.this, qlString);

				// clean up job
				if (this.jpqlCache.size() == EntityManagerFactoryImpl.NO_QUERIES_MAX) {
					synchronized (this) {
						if (this.jpqlCache.size() == EntityManagerFactoryImpl.NO_QUERIES_MAX) {
							final JpqlQuery[] queries = Lists.newArrayList(this.jpqlCache.values()).toArray(new JpqlQuery[this.jpqlCache.size()]);
							Arrays.sort(queries, new Comparator<JpqlQuery>() {

								@Override
								public int compare(JpqlQuery o1, JpqlQuery o2) {
									if (o1.getLastUsed() > o2.getLastUsed()) {
										return 1;
									}

									return -1;
								}
							});

							for (int i = 0; i < EntityManagerFactoryImpl.NO_QUERIES_TRIM; i++) {
								this.jpqlCache.remove(queries[i].getQueryString());
							}
						}
					}
				}

				this.jpqlCache.put(qlString, jpqlQuery);
			}
			return jpqlQuery;
		}
		catch (final Exception e) {
			if (e.getCause() instanceof PersistenceException) {
				throw (PersistenceException) e.getCause();
			}

			if (e.getCause() instanceof IllegalArgumentException) {
				throw (IllegalArgumentException) e.getCause();
			}

			throw new PersistenceException("Cannot parse query: " + e.getMessage(), e);
		}
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public MetamodelImpl getMetamodel() {
		return this.metamodel;
	}

	/**
	 * Returns the named query with the name <code>name</code>.
	 * 
	 * @param name
	 *            the name of the query
	 * @return the named query
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public JpqlQuery getNamedQuery(String name) {
		return this.namedQueries.get(name);
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public PersistenceUnitUtil getPersistenceUnitUtil() {
		return this.persistenceUtil;
	}

	/**
	 * Returns the set of persist validators.
	 * 
	 * @return the set of persist validators.
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public Class<?>[] getPersistValidators() {
		return this.persistValidators;
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public Map<String, Object> getProperties() {
		return this.properties;
	}

	/**
	 * Returns the persistence property.
	 * 
	 * @param key
	 *            the key for the property
	 * @return the value of the property or null
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public Object getProperty(String key) {
		return this.properties.get(key);
	}

	/**
	 * Returns the set of remove validators.
	 * 
	 * @return the set of remove validators.
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public Class<?>[] getRemoveValidators() {
		return this.removeValidators;
	}

	/**
	 * Returns the time to decide if SQL is deemed as slow.
	 * 
	 * @return the time to decide if SQL is deemed as slow
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public long getSlowSqlThreshold() {
		return this.slowSqlThreshold;
	}

	/**
	 * Returns the sql logging type of the entity manager factory.
	 * 
	 * @return the sql logging type of the entity manager factory
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public SqlLoggingType getSqlLogging() {
		return this.sqlLogging;
	}

	/**
	 * Returns the set of update validators.
	 * 
	 * @return the set of update validators.
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public Class<?>[] getUpdateValidators() {
		return this.updateValidators;
	}

	/**
	 * Returns the validation factory.
	 * 
	 * @return the validation factory.
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public ValidatorFactory getValidationFactory() {
		return this.validationFactory;
	}

	private Class<?>[] getValidatorsFor(PersistenceParser parser, String group) {

		final String groups = (String) parser.getProperties().get(group);
		if (StringUtils.isBlank(groups)) {
			return new Class[] { Default.class };
		}

		final Set<Class<?>> validationGroups = Sets.newHashSet();
		final Iterator<String> i = Splitter.on(",").trimResults().split(groups).iterator();
		while (i.hasNext()) {
			final String className = i.next();
			try {
				validationGroups.add(this.classloader.loadClass(className));
			}
			catch (final ClassNotFoundException e) {
				throw new PersistenceException("Cannot load class for validation group: " + className);
			}
		}

		return validationGroups.toArray(new Class[validationGroups.size()]);
	}

	/**
	 * Returns if the persistence unit has validators
	 * 
	 * @return true if the persistence unit has validators, false otherwise
	 * 
	 * @since $version
	 * @author hceylan
	 */
	public boolean hasValidators() {
		return this.validationFactory != null;
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	public boolean isOpen() {
		return this.open;
	}

	private void prepareProperties(PersistenceParser parser) {
		final Enumeration<?> e = System.getProperties().propertyNames();

		while (e.hasMoreElements()) {
			final Object key = e.nextElement();
			if (key instanceof String) {
				this.properties.put((String) key, System.getProperties().get(key));
			}
		}

		this.properties.putAll(parser.getProperties());
	}

	private DDLMode readDdlMode() {
		final String ddlMode = (String) this.getProperty(BJPASettings.DDL);

		if (ddlMode == null) {
			return DDLMode.NONE;
		}

		return DDLMode.valueOf(ddlMode.toUpperCase());
	}

	/**
	 * {@inheritDoc}
	 * 
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> T unwrap(Class<T> cls) {
		return (T) this;
	}
}
