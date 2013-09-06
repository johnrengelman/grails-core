/*
 * Copyright 2011 the original author or authors.
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
package org.grails.plugins.tomcat

import org.codehaus.groovy.grails.cli.logging.GrailsConsoleAntBuilder
import grails.build.logging.GrailsConsole
import grails.util.BuildSettings
import groovy.transform.CompileStatic

/**
 * Serves a packaged war, in a forked JVM.
 */
class IsolatedWarTomcatServer extends TomcatServer {

    static DEFAULT_JVM_ARGS = ["-Xmx512m"]
    static DEFAULT_STARTUP_TIMEOUT_SECS = 300 // 5 mins

    protected final File warDir
    protected final String contextPath
    protected ant = new GrailsConsoleAntBuilder()

    protected Integer httpPort
    protected Integer httpsPort

    IsolatedWarTomcatServer(String warPath, String contextPath) {
        super()

        warDir = getWorkDirFile("war")
        ant.delete(dir: warDir, failonerror: false)
        ant.unzip(src: warPath, dest: warDir)

        this.contextPath = contextPath == '/' ? '' : contextPath
    }

    void doStart(String host, Integer httpPort, Integer httpsPort) {
        def outFile = new File(buildSettings.projectTargetDir, "tomcat-out.txt")
        def errFile = new File(buildSettings.projectTargetDir, "tomcat-err.txt")
        [outFile, errFile].each { ant.delete(file: it, failonerror: false) }

        def resultProperty = "tomcat.result"

        System.setProperty("TomcatKillSwitch.active", "true");
        Thread.start("tomcat process runner") {
            ant.java(classname: IsolatedTomcat.name, fork: true, failonerror: false, output: outFile, error: errFile, resultproperty: resultProperty) {

                classpath {
                    for (jar in findTomcatJars(buildSettings)) {
                        pathelement location: jar
                    }
                }

                arg value: tomcatDir
                arg value: warDir.absolutePath
                arg value: contextPath
                arg value: host
                arg value: httpPort

                if (httpsPort != null) {
                    arg value: httpsPort
                    arg value: keystoreFile.absolutePath
                    arg value: keyPassword
                }

                for (a in (getConfigParam('jvmArgs') ?: DEFAULT_JVM_ARGS)) {
                    jvmarg value: a
                }

                for (entry in getConfigParams()) {
                    sysproperty key:"tomcat.${entry.key}", value:"${entry.value}"
                }
            }
        }

        Runtime.addShutdownHook { this.stop() }
        Thread.start {
            // start a thread to monitor kill if server was killed
            sleep(10000)
            while(true) {
                try {
                    new Socket(host, httpPort)
                    sleep(5000)
                } catch (e) {
                    println "bad"
                    System.setProperty("TomcatKillSwitch.active", "false");
                    break
                }
            }
        }

        def timeoutSecs = getConfigParam('startupTimeoutSecs') ?: DEFAULT_STARTUP_TIMEOUT_SECS
        def interval = 500 // half a second

        def loops = Math.ceil((timeoutSecs * 1000) / interval)
        def started = false
        def i = 0

        while (!started && i++ < loops) {
            // make sure tomcat didn't error starting up
            def resultCode = ant.project.properties."$resultProperty"
            if (resultCode != null) {
                def err = ""
                try { err = errFile.text } catch (IOException e) {}
                throw new RuntimeException("tomcat exited prematurely with code '$resultCode' (error output: '$err')")
            }

            // look for the magic string that will be written to output when the app is running
            try {
                String text = outFile.text
                started = text.contains("Server running. ")
                if (started) {
                    this.httpPort = parsePort("http", text)
                    this.httpsPort = parsePort("https", text)
                }
            } catch (IOException e) {
                started = false
            }

            if (!started) { // wait a bit then try again
                Thread.sleep(interval as long)
            }
        }

        if (!started) { // we didn't start in the specified timeout
            throw new RuntimeException("Tomcat failed to start the app in $timeoutSecs seconds (see output in $outFile.path)")
        }

        GrailsConsole.instance.log "Tomcat Server running WAR (output written to: $outFile)"

    }

    static Integer parsePort(String scheme, String text) {
        def pattern = "${scheme}:\\/\\/.*?:(\\d+)"
        def match = text =~ pattern
        if (match.find()) {
            return match[0][1].toInteger()
        } else {
            return null
        }
    }

    @CompileStatic
    public static Collection<File> findTomcatJars(BuildSettings buildSettings) {
        return buildSettings.buildDependencies.findAll { File it -> it.name.contains("tomcat") } +
                buildSettings.compileDependencies.findAll { File it -> it.name.contains("tomcat") } +
                    buildSettings.runtimeDependencies.findAll { File it -> it.name.contains("tomcat") } +
                        buildSettings.providedDependencies.findAll { File it -> it.name.contains("tomcat") }
    }

    void stop() {
        try {
            new URL("http://${warParams.host}:${warParams.port + 1}").text
        } catch(e) {
            // ignore
        }
    }

    int getLocalHttpPort() {
        return httpPort
    }

    int getLocalHttpsPort() {
        return httpsPort
    }
}

