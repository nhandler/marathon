package mesosphere.mesos

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.mesos.protos._
import org.apache.mesos.{ Protos => MesosProtos }

import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.duration._

class TaskBuilderWithArgsTestSuite extends TaskBuilderSuiteBase {

  import mesosphere.mesos.protos.Implicits._

  "TaskBuilder" when {

    "given an offer and an app definition with args" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          args = Some(Seq("a", "b", "c")),
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          executor = "//cmd",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, Seq[Option[Int]])] = buildIfMatches(offer, appDef)
      val (taskInfo, taskPorts) = task.get
      val rangeResourceOpt = taskInfo.getResourcesList.asScala.find(r => r.getName == Resource.PORTS)
      val ranges = rangeResourceOpt.fold(Seq.empty[MesosProtos.Value.Range])(_.getRanges.getRangeList.asScala.to[Seq])
      val rangePorts = ranges.flatMap(r => r.getBegin to r.getEnd).toSet
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      "return a defined task" in { task should be('defined) }

      "define a proper ports range" in { rangePorts.size should be(2) }
      "define task ports" in { taskPorts.size should be(2) }
      "define the same range and task ports" in { assert(taskPorts.flatten.toSet == rangePorts.toSet) }

      "not set an executor" in { taskInfo.hasExecutor should be(false) }
      "set a proper command with arguments" in {
        taskInfo.hasCommand should be(true)
        val cmd = taskInfo.getCommand
        assert(!cmd.getShell)
        assert(cmd.hasValue)
        assert(cmd.getArgumentsList.asScala == Seq("a", "b", "c"))
      }

      "set the correct resource roles" in {
        for (r <- taskInfo.getResourcesList.asScala) {
          assert(ResourceRole.Unreserved == r.getRole)
        }
      }

      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1)) }
    }

    "given an offer and an app definition with a command executor" should {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000)
        .addResources(ScalarResource("cpus", 1))
        .addResources(ScalarResource("mem", 128))
        .addResources(ScalarResource("disk", 2000))
        .build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          cmd = Some("foo"),
          executor = "/custom/executor",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo: MesosProtos.TaskInfo, _) = task.get

      "return a defined task" in { task should be('defined) }

      "not set a command" in { taskInfo.hasCommand should be(false) }
      "set an executor" in { taskInfo.hasExecutor should be(true) }

      val cmd = taskInfo.getExecutor.getCommand
      "set the executor command shell" in { cmd.getShell should be(true) }
      "set the executor command value" in { cmd.getValue should be("chmod ug+rx '/custom/executor' && exec '/custom/executor' foo") }
      "not set an argument list" in { cmd.getArgumentsList.asScala should be('empty) }
    }

    "given an offer and an app definition with arguments and an executor" should {

      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1.0, mem = 128.0, disk = 2000.0, beginPort = 31000, endPort = 32000).build
      val appDef =
        AppDefinition(
          id = "testApp".toPath,
          cpus = 1.0,
          mem = 64.0,
          disk = 1.0,
          args = Some(Seq("a", "b", "c")),
          executor = "/custom/executor",
          portDefinitions = PortDefinitions(8080, 8081)
        )

      val task: Option[(MesosProtos.TaskInfo, _)] = buildIfMatches(offer, appDef)
      val (taskInfo: MesosProtos.TaskInfo, _) = task.get
      val cmd = taskInfo.getExecutor.getCommand

      "return a defined task" in { task should be('defined) }

      "no set a command" in { taskInfo.hasCommand should be(false) }
      "set an executor command" in { cmd.getValue should be("chmod ug+rx '/custom/executor' && exec '/custom/executor' a b c") }
    }

    "given a command with fetch uris" should {
      val command = TaskBuilder.commandInfo(
        runSpec = AppDefinition(
          fetch = Seq(
            FetchUri(uri = "http://www.example.com", extract = false, cache = true, executable = false),
            FetchUri(uri = "http://www.example2.com", extract = true, cache = true, executable = true)
          )
        ),
        taskId = Some(Task.Id("task-123")),
        host = Some("host.mega.corp"),
        hostPorts = Helpers.hostPorts(1000, 1001),
        envPrefix = None
      )

      "set the command uris" in {
        command.getUris(0).getValue should be ("http://www.example.com")
        command.getUris(1).getValue should be ("http://www.example2.com")
      }
      "set the command uris cache" in {
        command.getUris(0).getCache should be(true)
        command.getUris(1).getCache should be(true)
      }
      "set one to be non-extractable" in { command.getUris(0).getExtract should be(false) }
      "set one to be extractable" in { command.getUris(1).getExtract should be(true) }
      "set one to be non-executable" in { command.getUris(0).getExecutable should be(false) }
      "set one to be executable" in { command.getUris(1).getExecutable should be(true) }
    }

    "given an offer and an app definition with task kill grace period" should {
    //  test("taskKillGracePeriod specified in app definition is passed through to TaskInfo") {
      val seconds = 12345.seconds
      val app: AppDefinition = MarathonTestHelper.makeBasicApp().copy(
        taskKillGracePeriod = Some(seconds)
      )

      val offer = MarathonTestHelper.makeBasicOffer(1.0, 128.0, 31000, 32000).build
      val builder = new TaskBuilder(app, s => Task.Id(s.toString), MarathonTestHelper.defaultConfig())
      val task = builder.buildIfMatches(offer, Set.empty)

      val (taskInfo: MesosProtos.TaskInfo, taskPorts) = task.get
      def resource(name: String): Resource = taskInfo.getResourcesList.asScala.find(_.getName == name).get

      "define task" in { task should be('defined) }

      "define a kill policy" in { taskInfo.hasKillPolicy should be(true) }
      val killPolicy = taskInfo.getKillPolicy

      "define a grace period" in { killPolicy.hasGracePeriod should be(true) }

      "set the grace perio in nano seconds" in {
        val gracePeriod = killPolicy.getGracePeriod
        gracePeriod.hasNanoseconds should be(true)
        gracePeriod.getNanoseconds should equal(seconds.toNanos)
      }

//      val portsFromTaskInfo = {
//        val asScalaRanges = for {
//          resource <- taskInfo.getResourcesList.asScala if resource.getName == Resource.PORTS
//          range <- resource.getRanges.getRangeList.asScala
//        } yield range.getBegin to range.getEnd
//        asScalaRanges.flatMap(_.iterator).toSet
//      }
//      "set the same ports in task info and task ports" in {
//        assert(portsFromTaskInfo == taskPorts.flatten.toSet)
//      }
//
//      "set the task name" in {
//        // The taskName is the elements of the path, reversed, and joined by dots
//        taskInfo.getName should be("frontend.product")
//      }
//
//      "not set an executor" in { taskInfo.hasExecutor should be(false) }
//      "set a command" in { taskInfo.hasCommand should be(true) }
//
//      val cmd = taskInfo.getCommand
//      "set the command shell" in { cmd.getShell should be(true) }
//      "set the command value" in {
//        cmd.hasValue should be(true)
//        cmd.getValue should be("foo")
//      }
//      "not set an argument list" in { cmd.getArgumentsList.asScala should be('empty) }
//
//      val env: Map[String, String] =
//        taskInfo.getCommand.getEnvironment.getVariablesList.asScala.toList.map(v => v.getName -> v.getValue).toMap
//      "set env variable HOST" in { env("HOST") should be(offer.getHostname) }
//      "set env variable PORT0" in { env.keys should contain("PORT0") }
//      "set env variable PORT1" in { env.keys should contain("PORT1") }
//      "set env variable PORT_8080" in { env.keys should contain("PORT_8080") }
//      "set env variable PORT_8081" in { env.keys should contain("PORT_8081") }
//       // assert(envVars.exists(v => v.getName == "HOST" && v.getValue == offer.getHostname))
////        assert(envVars.exists(v => v.getName == "PORT0" && v.getValue.nonEmpty))
////        assert(envVars.exists(v => v.getName == "PORT1" && v.getValue.nonEmpty))
////        assert(envVars.exists(v => v.getName == "PORT_8080" && v.getValue.nonEmpty))
////        assert(envVars.exists(v => v.getName == "PORT_8081" && v.getValue.nonEmpty))
//
//      "expose first port PORT0" in { env("PORT0") should be(env("PORT_8080")) }
//      "expose second port PORT1" in { env("PORT1") should be(env("PORT_8081")) }
//
//      "set resource roles to unreserved" in {
//        for (r <- taskInfo.getResourcesList.asScala) {
//          assert(ResourceRole.Unreserved == r.getRole)
//        }
//      }
//
//      "set discovert info" in { taskInfo.hasDiscovery should be(true) }
//      val discoveryInfo = taskInfo.getDiscovery
//
//
//      "set discovery info" in { taskInfo.hasDiscovery should be(true) }
//      "set discovery info name" in { discoveryInfo.getName should be(taskInfo.getName) }
//      "set discovery info visibility" in { discoveryInfo.getVisibility should be(MesosProtos.DiscoveryInfo.Visibility.FRAMEWORK) }
//
//      "set the correct port names" in {
//        discoveryInfo.getPorts.getPorts(0).getName should be("http")
//      }
//      "set correct discovery port protocols" in {
//        discoveryInfo.getPorts.getPorts(0).getProtocol should be("tcp")
//        discoveryInfo.getPorts.getPorts(1).getProtocol should be("tcp")
//      }
//      "set correct discovery port numbers" in {
//        discoveryInfo.getPorts.getPorts(0).getNumber should be(taskPorts(0))
//        discoveryInfo.getPorts.getPorts(1).getNumber should be(taskPorts(1))
//      }
//
//      "set an appropriate cpu share" in { resource("cpus") should be(ScalarResource("cpus", 1)) }
//      "set an appropriate mem share" in { resource("mem") should be(ScalarResource("mem", 64)) }
//      "set an appropriate disk share" in { resource("disk") should be(ScalarResource("disk", 1.0)) }
    }
  }
}