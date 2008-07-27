import java.lang.reflect.Field

//grails doesn't set the PluginDir when plugin is not installed as one yet.
//this is to fix loading issues when developing the plugin itself
try {
	includeTargets << new File("${gwtPluginDir}/scripts/_GwtInternal.groovy")
} catch (Exception ex) {
	binding.setVariable("gwtPluginDir", new File("${basedir}").absoluteFile)
	includeTargets << new File("${basedir}/scripts/_GwtInternal.groovy")
}

/** define the necessary addtask in order to modify grails.classpath path element     */
if (!Ant.antProject.properties."addPathJarSet") {
	Ant.path(id: 'addPathJarSet') {
		fileset(dir: "${gwtPluginDir}/lib", includes: "*.jar")
	}
}
Ant.taskdef(name: 'addpath', classname: 'org.apache.ivy.ant.AddPathTask', classpathref: 'addPathJarSet')

checkGwtHome()

/**
 * adds GWT libs and other GWT-related code into the running classpath and the compiling classpath.
 * This effectively allows Grails to compile GWT modules without having to include any GWT libs.
 * In addition, it allows dynamic recompilation of GWT modules when running under "grails run-app"
 */
eventSetClasspath = {rootLoader ->
	setGwtClassLoader()
}

// Compile gwt modules grails compile starts
eventCompileStart = {type ->
	if (type != 'source') return
	setGwtClassLoader()
	//add GWT to the grails.classpath path for compilation use only
	Ant.addpath(toPath: 'grails.classpath') {
		fileset(dir: "${gwtHome}", includes: 'gwt-*.jar')
	}
	compileGwtModules()
}

eventCompileEnd = {type ->
	try {
		//must add servlet library to root loader here in order to allow compile during install-plugin
		//also added here so that Jetty can find and load 
		classLoader.rootLoader.addURL(new File("${gwtHome}/gwt-servlet.jar").toURI().toURL())
	} catch (Exception ex) {
		ex.printStackTrace()
	}
}

// Clean up the GWT-generated files on "clean".
eventCleanEnd = {
	gwtClean()
}

/** 
 * Grails Jetty uses jasper-compiler which contains JDT Compiler that conflicts with GWT's own
 * JDT Compiler version.  This causes the GWT Compiler unable to perform dynamic recompilations
 * when running inside grails run-app.  To get around this, the gwt-dev-*.jar library MUST be
 * loaded before jasper-compiler.  Unfortunately, Grails events do not allow you to inject 
 * classpaths BEFORE the grails libraries.  Thus, our current workaround is to inject our
 * special GWT classloader before the rootLoader.
 */
target(setGwtClassLoader: 'Set set our own custom GWT class loader') {
	def rl = getClass().classLoader.rootLoader

	//don't double load ourselves
	if (URLClassLoader.class.name == rl.parent?.getClass().name) return

	URLClassLoader cl = new URLClassLoader([] as URL[], rl.parent)
	//gwt servlet library must be reside in the same classloader as Jetty or it won't be able to load
	def jarFiles = Ant.fileScanner {
		fileset(dir: "${gwtHome}", includes: 'gwt-*.jar')
		fileset(dir: "${basedir}/lib", includes: "gwt/*.jar")
	}
	jarFiles.each {
		cl?.addURL(it.toURI().toURL())
	}
	cl?.addURL(new File("${basedir}/src/gwt").toURI().toURL())

	//regraft rootLoader parent to our custom loader
	try {
		Field parent = ClassLoader.class.declaredFields.find {if (it.name == 'parent') return true}
		parent.setAccessible(true)
		parent.set(rl, cl)
	} catch (Throwable ex) {
		println "Unable to set GWT-specific classloader.  Compiling GWT Classes might cause conflicts"
	}
}
