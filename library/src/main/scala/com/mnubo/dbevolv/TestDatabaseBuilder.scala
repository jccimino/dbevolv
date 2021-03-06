package com.mnubo
package dbevolv

import com.mnubo.dbevolv.docker.{Container, Docker}
import com.mnubo.dbevolv.util.Logging
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object TestDatabaseBuilder extends Logging {
  def main(args: Array[String]) = { // Don't ask me why, but a weird bug in Scala compiler prevents me to extend App instead.
    val doPush = args.contains("push")
    val config= DbevolvConfiguration
      .loadConfig("workstation")
      .withValue("force_pull_verification_db", ConfigValueFactory.fromAnyRef(false))

    val defaultTestConfig = ConfigFactory.parseString("""
      name_provider_class = "com.mnubo.dbevolv.DefaultDatabaseNameProvider"
      has_instance_for_each_tenant=false
    """).withFallback(config)

    val testConfigs = config
      .getConfigList("test_configurations")
      .asScala
      .map(_.withFallback(config)) :+ defaultTestConfig

    val dbKind = config.getString("database_kind")
    val dockerNamespace = if (config.hasPath("docker_namespace")) Some(config.getString("docker_namespace")) else None
    val dbNameProvider =
      getClass
        .getClassLoader
        .loadClass(config.getString("name_provider_class"))
        .newInstance()
        .asInstanceOf[DatabaseNameProvider]

    val schemaName = config.getString("schema_name")
    val dbSchemaName = dbNameProvider.computeDatabaseName(schemaName, None, config)

    val imageName = s"test-$schemaName"
    val repositoryName = dockerNamespace.map(ns => s"$ns/$imageName").getOrElse(imageName)
    val db = Database.databases(dbKind)

    using(new Docker(if (config.hasPath("docker_namespace")) Some(config.getString("docker_namespace")) else None )) { docker =>
      log.info(s"Starting a fresh test $dbKind $schemaName instance ...")
      val container = new Container(docker, db.testDockerBaseImage)

      try {
        val availableMigrations = DatabaseMigrator.getAvailableMigrations

        log.info(s"Creating and migrating test database '$dbSchemaName' to latest version ...")
        val images = availableMigrations.map { schemaVersion =>
          if (schemaVersion.isRebase && schemaVersion != availableMigrations.head) {
            // Verify the rebase before we migrate the ref db to the latest version
            log.info(s"Verifying rebase script ${schemaVersion.version}")
            val fromRebaseContainer = new Container(docker, db.testDockerBaseImage)

            try {
              // Execute rebase script on new db
              if (schemaVersion == availableMigrations.last) {
                log.info("Migrating to last version")
                migrate(None, twice = false, config = defaultTestConfig, container = fromRebaseContainer)
              } else {
                log.info("Migrating to next version")
                migrate(Some(schemaVersion.version), twice = false, config = defaultTestConfig, container = fromRebaseContainer)
              }

              // Verify schemas are compatible
              withConnection(fromRebaseContainer, config) { fromConnection =>
                fromConnection.setActiveSchema(dbSchemaName, config)

                withConnection(container, config) { connection =>
                  connection.setActiveSchema(dbSchemaName, config)
                  if (!connection.isSameSchema(fromConnection))
                    throw new Exception(s"Rebase script for version ${schemaVersion.version} is not compatible with previous version schema")
                }
              }
            }
            finally {
              try fromRebaseContainer.stop()
              finally fromRebaseContainer.remove()
            }
          }

          testConfigs.foreach { testConfig =>
            if (testConfig.getBoolean("has_instance_for_each_tenant") && testConfig.hasPath("tenant"))
              migrate(Some(schemaVersion.version), twice = true, config = testConfig, tenant = Some(testConfig.getString("tenant")))
            else
              migrate(Some(schemaVersion.version), twice = true, config = testConfig)
          }

          log.info(s"Commiting $dbKind $schemaName test instance to $repositoryName:${schemaVersion.version}...")
          db.testDockerBaseImage.flushCmd.foreach { cmd =>
            container.exec(cmd)
          }
          val imageId = container.commit(repositoryName, schemaVersion.version)

          if (doPush) {
            log.info(s"Publishing $dbKind $schemaName test instance to $repositoryName:${schemaVersion.version} ...")
            docker.push(s"$repositoryName:${schemaVersion.version}")
          }

          imageId
        }

        if (doPush) {
          log.info(s"Tagging and pushing latest version...")
          container.tag(s"$repositoryName:latest")
          docker.push(s"$repositoryName:latest")
        }

        log.info(s"Testing rollback procedure...")
        val firstVersion = availableMigrations.head
        migrate(Some(firstVersion.version), twice = false, config = defaultTestConfig)

        if (doPush) {
          images.foreach { imageId =>
            log.info(s"Cleaning up image $imageId ...")
            docker.removeImage(imageId)
          }
        }
      }
      catch {
        case NonFatal(ex) =>
          log.error(s"Test database build failed", ex)
          throw ex
      }
      finally {
        log.info(s"Cleaning up container ${container.containerId} ...")
        container.stop()
        container.remove()
      }

      def migrate(toVersion: Option[String], twice: Boolean, config: Config, tenant: Option[String] = None, container: Container = container) = {
        val tenantConfig =
          tenant
            .map { tenantId =>
              using(
                getClass
                  .getClassLoader
                  .loadClass(config.getString("tenant_configuration_provider_class"))
                  .getConstructor(classOf[Config])
                  .newInstance(config)
                  .asInstanceOf[TenantConfigurationProvider]
              ) { _.configFor(tenantId).withFallback(config) }
            }
            .getOrElse(config)

        withConnection(container, tenantConfig) { connection =>
          log.info(s"Connected to $container.")

          DatabaseMigrator.migrate(DbMigrationConfig(
            connection,
            tenantConfig,
            tenant,
            toVersion,
            skipSchemaVerification = true,
            applyUpgradesTwice = twice
          ))
        }
      }

      def withConnection(container: Container, config: Config)(action: DatabaseConnection => Unit) =
        using(db.openConnection(
          docker,
          dbSchemaName,
          container.containerHost,
          container.exposedPort,
          db.testDockerBaseImage.username,
          db.testDockerBaseImage.password,
          config
        ))(action)
    }

  }
}
