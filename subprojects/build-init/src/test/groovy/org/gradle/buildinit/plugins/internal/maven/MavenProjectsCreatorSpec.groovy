/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.buildinit.plugins.internal.maven

import org.gradle.api.internal.artifacts.mvnsettings.DefaultMavenSettingsProvider
import org.gradle.api.internal.artifacts.mvnsettings.MavenFileLocations
import org.gradle.buildinit.plugins.internal.modifiers.BuildInitBuildScriptDsl
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.junit.Rule
import spock.lang.Specification

class MavenProjectsCreatorSpec extends Specification {

    @Rule TestNameTestDirectoryProvider temp
    private settings = new DefaultMavenSettingsProvider({} as MavenFileLocations)
    private creator = new MavenProjectsCreator()

    def "creates single module project"() {
        given:
        def pom = temp.file("pom.xml")
        pom.text = """<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>util</groupId>
  <artifactId>util</artifactId>
  <version>2.5</version>
  <packaging>jar</packaging>
</project>"""

        when:
        def mavenProjects = creator.create(settings.buildSettings(), pom) as List

        then:
        mavenProjects.size() == 1
        mavenProjects[0].name == 'util'
    }

    def "creates multi module project"() {
        given:
        def parentPom = temp.file("pom.xml")
        parentPom.text = """<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.gradle.webinar</groupId>
  <artifactId>webinar-parent</artifactId>
  <version>1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>webinar-api</module>
  </modules>
</project>
"""

        temp.file("webinar-api/pom.xml").text = """<project>
  <modelVersion>4.0.0</modelVersion>
  <artifactId>webinar-api</artifactId>
  <packaging>jar</packaging>

  <parent>
    <groupId>org.gradle.webinar</groupId>
    <artifactId>webinar-parent</artifactId>
    <version>1.0-SNAPSHOT</version>
  </parent>
</project>
"""

        when:
        def mavenProjects = creator.create(settings.buildSettings(), parentPom) as List

        then:
        mavenProjects.size() == 2
        mavenProjects[0].name == 'webinar-parent'
        mavenProjects[1].name == 'webinar-api'
    }

    def "fails with decent exception if pom is incorrect"() {
        given:
        def pom = temp.file("pom.xml")
        pom.text = """<project>
  <modelVersion>4.0.0</modelVersion>
  <artifactId>util</artifactId>
  <version>2.5</version>
  <packaging>jar</packaging>
</project>"""

        when:
        creator.create(settings.buildSettings(), pom) as List

        then:
        def ex = thrown(MavenConversionException)
        ex.message == "Unable to create Maven project model using POM $pom."
    }

    def "fails with decent exception if pom does not exist"() {
        def pom = temp.file("pom.xml")

        when:
        creator.create(settings.buildSettings(), pom) as List

        then:
        def ex = thrown(MavenConversionException)
        ex.message == "Unable to create Maven project model. The POM file $pom does not exist."
    }

    def "can translate dependency assigned to Maven provided scope into compileOnly"() {
        given:
            def pom = temp.file("pom.xml")
            pom.text = """<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>util</groupId>
  <artifactId>util</artifactId>
  <version>2.5</version>
  <packaging>jar</packaging>
  <dependencies>
    <dependency>
        <groupId>org.gradle</groupId>
        <artifactId>build-init</artifactId>
        <version>1.0.0</version>
        <scope>provided</scope>
    </dependency>
  </dependencies>
</project>"""
        def mavenProjects = creator.create(settings.buildSettings(), pom)
        def converter = new Maven2Gradle(BuildInitBuildScriptDsl.GROOVY, mavenProjects, temp.testDirectory)

        when:
        def gradleProject = converter.convert()

        then:
        gradleProject.contains("compileOnly group: 'org.gradle', name: 'build-init', version:'1.0.0'")
    }
}
