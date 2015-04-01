package com.mnubo
package dbschemas.docker

import java.io.{BufferedInputStream, BufferedReader, InputStream, InputStreamReader}
import java.net.ServerSocket

import com.github.dockerjava.api.command.DockerCmdExecFactory
import com.github.dockerjava.api.model.{ExposedPort, PortBinding, Ports}
import com.github.dockerjava.core.{DockerClientConfig, DockerClientBuilder}
import com.mnubo.app_util.Logging

import scala.annotation.tailrec

object Docker extends Logging {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val hostVar = System.getenv("DOCKER_HOST")

  private val config =
    if (hostVar != null)
      DockerClientConfig
        .createDefaultConfigBuilder()
        .build()
    else
      DockerClientConfig
        .createDefaultConfigBuilder()
        .withUri("unix:///var/run/docker.sock")
        .build()

  private val dockerClient = DockerClientBuilder
    .getInstance(config)
    .withServiceLoaderClassLoader(getClass.getClassLoader)
    .build()

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  private def getAvailablePort =
    using(new ServerSocket(0))(_.getLocalPort)

  def run(dockerImage: String, exposedPort: Int, isStarted: String => Boolean): ContainerInfo = {
    val dbMainPort = ExposedPort.tcp(exposedPort)
    val hostPort = getAvailablePort

    if (dockerImage.contains(":")) {
      val Array(image, tag) = dockerImage.split(':')
      dockerClient
        .pullImageCmd(image)
        .withTag(tag)
        .exec()
    }
    else {
      dockerClient
        .pullImageCmd(dockerImage)
        .exec()
    }
    val container = dockerClient
      .createContainerCmd(dockerImage)
      .withExposedPorts(dbMainPort)
      .withTty(true)
      .exec()
      .getId

    dockerClient
      .startContainerCmd(container)
      .withPortBindings(new PortBinding(Ports.Binding(hostPort), dbMainPort))
      .exec()

    @tailrec
    def waitStarted: Unit = {
      val stream = dockerClient
        .logContainerCmd(container)
        .withStdErr()
        .withStdOut()
        .exec()

      val log = asString(stream)

      if (!isStarted(log)) {
        Thread.sleep(100)
        waitStarted
      }
    }

    waitStarted

    ContainerInfo(container, hostPort)
  }

  def stop(container: String): Unit =
    dockerClient
      .stopContainerCmd(container)
      .exec()

  def commit(container: String, repository: String, tag: String): String = {
    val imageId = dockerClient
      .commitCmd(container)
      .withRepository(repository)
      .withTag(tag)
      .exec()

    dockerClient
      .tagImageCmd(imageId, repository, "latest")
      .withForce(true)
      .exec()

    imageId
  }

  def push(repository: String): Unit =
    dockerClient
      .pushImageCmd(repository)
      .exec()

  def remove(container: String): Unit =
    dockerClient
      .removeContainerCmd(container)
      .exec()

  def removeImage(image: String): Unit =
    dockerClient
      .removeImageCmd(image)
      .exec()

  private def asString(stream: InputStream) =
    using(stream) { _ =>
      using(new BufferedReader(new InputStreamReader(new BufferedInputStream(stream)))) { br =>
        val buf = new StringBuilder
        var line = br.readLine()
        while (line != null) {
          buf.append(line).append("\n")
          line = br.readLine()
        }
        buf.toString
      }
    }

}

case class ContainerInfo(id: String, realPort: Int)
