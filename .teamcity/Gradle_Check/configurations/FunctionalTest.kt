package configurations

import jetbrains.buildServer.configs.kotlin.v10.BuildType
import model.CIBuildModel
import model.OS
import model.TestCoverage
import model.TestType

class FunctionalTest(model: CIBuildModel, testCoverage : TestCoverage, subProject: String = "") : BuildType({
    uuid = "${testCoverage.asId(model)}_" + if (!subProject.isEmpty()) subProject else "0"
    extId = uuid
    name = testCoverage.asName() + if (!subProject.isEmpty()) " ($subProject)" else ""
    val testTask = testCoverage.testType.name + "Test" + if (!subProject.isEmpty()) subProject.capitalize() else ""
    val quickTest = testCoverage.testType == TestType.quick
    applyDefaults(model, this, testTask, requiresDistribution = !quickTest,
            runsOnWindows = testCoverage.os == OS.windows, timeout = if (quickTest) 60 else 180)

    params {
        param("env.JAVA_HOME", "%${testCoverage.os}.${testCoverage.version}.${testCoverage.vendor}.64bit%")
        if (testCoverage.os == OS.linux) {
            param("env.JAVA_9", "%linux.java9.oracle.64bit%")
            param("env.ANDROID_HOME", "/opt/android/sdk")
        }
    }
})