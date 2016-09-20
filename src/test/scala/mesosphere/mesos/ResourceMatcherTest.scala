package mesosphere.mesos

import mesosphere.marathon.MarathonTestHelper.Implicits._
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.AppDefinition.VersionInfo.{ FullVersionInfo, OnlyVersion }
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.state.{ AppDefinition, Container, PortDefinitions, ResourceRole, Timestamp }
import mesosphere.marathon.stream._
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.{ Resource, TextAttribute }
import org.apache.mesos.Protos.Attribute
import org.scalatest.Matchers
import mesosphere.mesos.protos.Implicits._
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.{ Attribute, ContainerInfo }
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.{ Matchers, Inside }

import scala.collection.immutable.Seq

class ResourceMatcherTest extends MarathonSpec with Matchers with Inside {
  test("match with app.disk == 0, even if no disk resource is contained in the offer") {
    val offerBuilder = MarathonTestHelper.makeBasicOffer()
    val diskResourceIndex = offerBuilder.getResourcesList.toIndexedSeq.indexWhere(_.getName == "disk")
    offerBuilder.removeResources(diskResourceIndex)
    val offer = offerBuilder.build()

    offer.getResourcesList.find(_.getName == "disk") should be('empty)

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.DISK) should be(empty)

    res.hostPorts should have size 2
  }

  test("match resources success") {
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.DISK) should be(empty)

    res.hostPorts should have size 2
  }

  test("match resources success with BRIDGE and portMappings") {
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = Nil,
      container = Some(Container.Docker(
        image = "foo/bar",
        network = Some(ContainerInfo.DockerInfo.Network.BRIDGE),
        portMappings = Some(Seq(
          Container.Docker.PortMapping(31001, Some(0), 0, "tcp", Some("qax")),
          Container.Docker.PortMapping(31002, Some(0), 0, "tcp", Some("qab"))
        ))
      ))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.DISK) should be(empty)

    res.hostPorts should have size 2
  }

  test("match resources success with USER and portMappings") {
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = Nil,
      container = Some(Container.Docker(
        image = "foo/bar",
        network = Some(ContainerInfo.DockerInfo.Network.USER),
        portMappings = Some(Seq(
          Container.Docker.PortMapping(0, Some(0), 0, "tcp", Some("yas")),
          Container.Docker.PortMapping(31001, None, 0, "tcp", Some("qax")),
          Container.Docker.PortMapping(31002, Some(0), 0, "tcp", Some("qab"))
        ))
      ))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
    res.scalarMatch(Resource.DISK) should be(empty)

    res.hostPorts should have size 3
    res.hostPorts.flatten should have size 2 // linter:ignore:AvoidOptionMethod
  }

  test("match resources success with preserved reservations") {
    val labels = TaskLabels.labelsForTask(FrameworkId("foo"), Task.Id("bar")).labels
    val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels)
    val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels)
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels)
    val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels)
    val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal", labels)

    val offer =
      MarathonTestHelper.makeBasicOffer(role = "marathon")
        .clearResources()
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
        .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
        .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
        .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
        .build()

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 2.0,
      mem = 128.0,
      disk = 2.0,
      portDefinitions = PortDefinitions(0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.reservedWithLabels(Set(ResourceRole.Unreserved, "marathon"), labels))

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatches should have size (3)
    res.scalarMatch(Resource.CPUS).get.consumed.toSet should be(
      Set(
        GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation)),
        GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation2))
      )
    )

    res.scalarMatch(Resource.MEM).get.consumed.toSet should be(
      Set(
        GeneralScalarMatch.Consumption(128.0, ResourceRole.Unreserved, reservation = Some(memReservation))
      )
    )
    res.scalarMatch(Resource.DISK).get.consumed.toSet should be(
      Set(
        DiskResourceMatch.Consumption(2.0, ResourceRole.Unreserved, Some(diskReservation), DiskSource.root, None)
      )
    )

    res.portsMatch.hostPortsWithRole.toSet should be(
      Set(Some(PortsMatcher.PortWithRole(ResourceRole.Unreserved, 80, reservation = Some(portsReservation))))
    )

    // reserved resources with labels should not be matched by selector if don't match for reservation with labels
    ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon"))) should be(None)
  }

  test("dynamically reserved resources are matched if they have no labels") {
    val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal")
    val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal")
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal")
    val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal")
    val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal")

    val offer =
      MarathonTestHelper.makeBasicOffer(role = "marathon")
        .clearResources()
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
        .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
        .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
        .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
        .build()

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 2.0,
      mem = 128.0,
      disk = 2.0,
      portDefinitions = PortDefinitions(0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")))

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatches should have size (3)
    res.scalarMatch(Resource.CPUS).get.consumed.toSet should be(
      Set(
        GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation)),
        GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation2))
      )
    )

    res.scalarMatch(Resource.MEM).get.consumed.toSet should be(
      Set(
        GeneralScalarMatch.Consumption(128.0, ResourceRole.Unreserved, reservation = Some(memReservation))
      )
    )
    res.scalarMatch(Resource.DISK).get.consumed.toSet should be(
      Set(
        DiskResourceMatch.Consumption(
          2.0, ResourceRole.Unreserved, reservation = Some(diskReservation), DiskSource.root, None)
      )
    )

    res.portsMatch.hostPortsWithRole.toSet should be(
      Set(Some(PortsMatcher.PortWithRole(ResourceRole.Unreserved, 80, reservation = Some(portsReservation))))
    )
  }

  test("dynamically reserved resources are NOT matched if they have known labels") {
    val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal")
    val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal")
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = TaskLabels.labelsForTask(FrameworkId("foo"), Task.Id("bar")).labels)
    val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal")
    val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal")

    val offer =
      MarathonTestHelper.makeBasicOffer(role = "marathon")
        .clearResources()
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
        .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
        .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
        .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
        .build()

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 2.0,
      mem = 128.0,
      disk = 2.0,
      portDefinitions = PortDefinitions(0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")))

    resOpt shouldBe empty
  }

  test("ResourceSelector.reservedWithLabels should not match disk resource without label") {
    val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels = Map("some" -> "label"))
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("some" -> "label"))

    val offer =
      MarathonTestHelper.makeBasicOffer(role = "marathon")
        .clearResources()
        .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
        .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
        .addResources(MarathonTestHelper.reservedDisk(id = "disk", size = 1024.0))
        .build()

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 2.0,
      portDefinitions = PortDefinitions()
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.reservedWithLabels(Set(ResourceRole.Unreserved, "marathon"), Map("some" -> "label"))
    )

    resOpt should be(empty)
  }

  test("match resources success with preserved roles") {
    val offer = MarathonTestHelper.makeBasicOffer(role = "marathon").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), ResourceSelector.any(Set("marathon")))

    resOpt should not be empty
    val res = resOpt.get

    res.scalarMatch(Resource.CPUS).get.roles should be(Seq("marathon"))
    res.scalarMatch(Resource.MEM).get.roles should be(Seq("marathon"))
    res.scalarMatch(Resource.DISK) should be(empty)
  }

  test("match resources failure because of incorrect roles") {
    val offer = MarathonTestHelper.makeBasicOffer(role = "marathon").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), wildcardResourceSelector)

    resOpt should be ('empty)
  }

  test("match resources success with constraints") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      constraints = Set(
        Constraint.newBuilder
          .setField("hostname")
          .setOperator(Operator.LIKE)
          .setValue("host1")
          .build()
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should not be empty
  }

  test("match resources fails on constraints") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      constraints = Set(
        Constraint.newBuilder
          .setField("hostname")
          .setOperator(Operator.LIKE)
          .setValue("host2")
          .build()
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should be (empty)
  }

  test("match resources fail on cpu") {
    val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should be (empty)
  }

  test("match resources fail on mem") {
    val offer = MarathonTestHelper.makeBasicOffer(mem = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should be (empty)
  }

  test("match resources fail on disk") {
    val offer = MarathonTestHelper.makeBasicOffer(disk = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      portDefinitions = PortDefinitions(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should be (empty)
  }

  test("match resources fail on ports") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      portDefinitions = PortDefinitions(1, 2)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, runningTasks = Iterable.empty, wildcardResourceSelector)

    resOpt should be (empty)
  }

  test("match resources success with constraints and old tasks in previous version") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0)
      .addAttributes(TextAttribute("region", "pl-east"))
      .addAttributes(TextAttribute("zone", "pl-east-1b"))
      .build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      versionInfo = OnlyVersion(Timestamp(2)),
      constraints = Set(
        Constraint.newBuilder
          .setField("region")
          .setOperator(Operator.GROUP_BY)
          .setValue("2")
          .build(),
        Constraint.newBuilder
          .setField("zone")
          .setOperator(Operator.GROUP_BY)
          .setValue("4")
          .build()
      )
    )
    val oldVersion = Timestamp(1)
    //We have 4 tasks spread across 2 DC and 3 zones
    //We want to launch new task (with  new version).
    //According to constraints it should be placed
    //in pl-east-1b
    val tasks = Set(

      task("1", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("2", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("3", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),

      task("4", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1a")),
      task("5", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1b"))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, tasks, wildcardResourceSelector)

    resOpt should not be empty
  }

  test("match resources fail with constraints and old tasks deployed since last config change") {
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0)
      .addAttributes(TextAttribute("region", "pl-east"))
      .addAttributes(TextAttribute("zone", "pl-east-1b"))
      .build()
    val oldVersion = Timestamp(1)
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      versionInfo = FullVersionInfo(
        version = Timestamp(5),
        lastScalingAt = Timestamp(5),
        lastConfigChangeAt = oldVersion
      ),
      constraints = Set(
        Constraint.newBuilder
          .setField("region")
          .setOperator(Operator.GROUP_BY)
          .setValue("2")
          .build(),
        Constraint.newBuilder
          .setField("zone")
          .setOperator(Operator.GROUP_BY)
          .setValue("4")
          .build()
      )
    )

    //We have 4 tasks spread across 2 DC and 3 zones
    //We want to scale our application.
    //But it will conflict with previously launched tasks.
    val tasks = Set(

      task("1", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("2", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
      task("3", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),

      task("4", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1a")),
      task("5", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1b"))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, tasks, wildcardResourceSelector)

    resOpt should be (empty)
  }

  test("match disk won't allocate resources across disk different paths") {
    val offerDisksTooSmall = MarathonTestHelper.makeBasicOffer().
      addResources(MarathonTestHelper.scalarResource("disk", 1024.0,
        disk = Some(MarathonTestHelper.pathDisk("/path1")))).
      addResources(MarathonTestHelper.scalarResource("disk", 1024.0,
        disk = Some(MarathonTestHelper.pathDisk("/path2")))).
      build()

    val offerSufficeWithMultOffers =
      offerDisksTooSmall.toBuilder().
        // add another resource for /path2, in addition to the resources from the previous offer
        addResources(MarathonTestHelper.scalarResource("disk", 500,
          disk = Some(MarathonTestHelper.pathDisk("/path2")))).
        build()

    val volume = PersistentVolume(
      containerPath = "/var/lib/data",
      mode = Mesos.Volume.Mode.RW,
      persistent = PersistentVolumeInfo(
        size = 1500,
        `type` = DiskType.Path))

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      container = Some(Container.Mesos(
        volumes = List(volume))),
      versionInfo = OnlyVersion(Timestamp(2)))

    ResourceMatcher.matchResources(
      offerDisksTooSmall, app,
      runningTasks = Set(),
      ResourceSelector.reservable) shouldBe (None)

    val result = ResourceMatcher.matchResources(
      offerSufficeWithMultOffers, app,
      runningTasks = Set(),
      ResourceSelector.reservable)

    result shouldNot be(None)
    result.get.scalarMatch("disk").get.consumed.toSet shouldBe (
      Set(
        DiskResourceMatch.Consumption(1024.0, "*", None, DiskSource(DiskType.Path, Some("/path2")), Some(volume)),
        DiskResourceMatch.Consumption(476.0, "*", None, DiskSource(DiskType.Path, Some("/path2")), Some(volume))))
  }

  test("match disk enforces constraints") {
    val offers = Seq("/mnt/disk-a", "/mnt/disk-b").map { path =>
      path -> MarathonTestHelper.makeBasicOffer().
        addResources(MarathonTestHelper.scalarResource("disk", 1024.0,
          disk = Some(MarathonTestHelper.pathDisk(path)))).
        build()
    }.toMap

    val volume = PersistentVolume(
      containerPath = "/var/lib/data",
      mode = Mesos.Volume.Mode.RW,
      persistent = PersistentVolumeInfo(
        size = 500,
        `type` = DiskType.Path,
        constraints = Set(MarathonTestHelper.constraint("path", "LIKE", Some(".+disk-b")))))

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      container = Some(Container.Mesos(
        volumes = List(volume))),
      versionInfo = OnlyVersion(Timestamp(2)))

    ResourceMatcher.matchResources(
      offers("/mnt/disk-a"), app,
      runningTasks = Set(),
      ResourceSelector.reservable) should be(None)

    ResourceMatcher.matchResources(
      offers("/mnt/disk-b"), app,
      runningTasks = Set(),
      ResourceSelector.reservable) shouldNot be(None)
  }

  test("mount disk enforces maxSize constraints") {
    val offer =
      MarathonTestHelper.makeBasicOffer().
        addResources(
          MarathonTestHelper.scalarResource("disk", 1024.0,
            disk = Some(MarathonTestHelper.mountDisk("/mnt/disk1")))).
          build()

    def mountRequest(size: Long, maxSize: Option[Long]) = {
      val volume = PersistentVolume(
        containerPath = "/var/lib/data",
        mode = Mesos.Volume.Mode.RW,
        persistent = PersistentVolumeInfo(
          size = size,
          maxSize = maxSize,
          `type` = DiskType.Mount))

      val app = AppDefinition(
        id = "/test".toRootPath,
        cpus = 1.0,
        mem = 128.0,
        disk = 0.0,
        container = Some(Container.Mesos(
          volumes = List(volume))),
        versionInfo = OnlyVersion(Timestamp(2)))
      app
    }

    inside(ResourceMatcher.matchResources(
      offer, mountRequest(500, None),
      runningTasks = Set(),
      ResourceSelector.reservable)) {
      case Some(ResourceMatcher.ResourceMatch(matches, _)) =>
        matches.collectFirst { case m: DiskResourceMatch => m.consumedValue } shouldBe (Some(1024))
    }

    ResourceMatcher.matchResources(
      offer, mountRequest(500, Some(750)),
      runningTasks = Set(),
      ResourceSelector.reservable) should be(None)

    ResourceMatcher.matchResources(
      offer, mountRequest(500, Some(1024)),
      runningTasks = Set(),
      ResourceSelector.reservable) shouldNot be(None)
  }

  def task(id: String, version: Timestamp, attrs: Map[String, String]): Task = {
    val attributes: Seq[Attribute] = attrs.map { case (name, value) => TextAttribute(name, value): Attribute }(collection.breakOut)
    MarathonTestHelper.stagedTask(id, appVersion = version)
      .withAgentInfo(_.copy(attributes = attributes))
  }

  lazy val wildcardResourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved))
}
