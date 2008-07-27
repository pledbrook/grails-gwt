import com.google.gwt.core.ext.TreeLogger
import com.google.gwt.core.ext.UnableToCompleteException
import com.google.gwt.dev.GWTCompiler
import com.google.gwt.dev.cfg.ModuleDef
import com.google.gwt.dev.cfg.ModuleDefLoader
import org.apache.commons.io.FileUtils
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.codehaus.groovy.grails.plugins.support.GrailsPluginUtils
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/*
 * Copyright 2007 Peter Ledbrook.
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
 *
 * This is the grails plugin that will dynamically detect file changes and dynamically recompile GWT modules.
 *
 * @author Peter Ledbrook
 * @author Chris Chen
 * @since 1.5RC1
 *
 */
class GwtGrailsPlugin {
	def version = '1.5RC1-SNAPSHOT'
	def author = 'Peter Ledbrook'
	def authorEmail = 'peter@cacoethes.co.uk'
	def title = 'The Google Web Toolkit for Grails.'
	def description = '''\
Incorporates GWT into Grails. In particular, GWT host pages can be
GSPs and standard Grails services can be used to handle client RPC
requests.
'''
	def documentation = 'http://grails.codehaus.org/GWT+Plugin'

	def grailsVersion = GrailsPluginUtils.grailsVersion
	def loadAfter = ['controllers', 'services']
	def watchedResources = ["file:./src/gwt/**/*.*", "file:./src/java/**/*.*"]

	private static final Log LOG = LogFactory.getLog("org.codehaus.groovy.grails.plugins.GwtGrailsPlugin")
	def moduleMap = null
	def resolver = new PathMatchingResourcePatternResolver()
	def outDir = "web-app/gwt"
	static {
		//add our own metaclass method to access the java source file
		ModuleDef.metaClass.findJavaSourceFile = {partialPath -> return findSourceFile(partialPath)}
	}


	def doWithSpring = {
	}

	def doWithApplicationContext = {applicationContext ->
	}

	def doWithWebDescriptor = {xml ->
		loadGwtModules()

		xml.servlet[0] + {
			servlet {
				'servlet-name'('GwtRpcServlet')
				'servlet-class'('org.codehaus.groovy.grails.plugins.gwt.GrailsRemoteServiceServlet')
			}
		}

		// Create a servlet mapping for each module defined in the
		// project.
		for (HashMap moduleInfoMap: moduleMap.values()) {
			ModuleDef moduleDef = moduleInfoMap.module
			xml.'servlet-mapping'[0] + {
				'servlet-mapping'
				{
					'servlet-name'('GwtRpcServlet')
					'url-pattern'("/gwt/${moduleDef.name}/rpc")
				}
			}
		}
	}

	def doWithDynamicMethods = {ctx ->
	}

	def onChange = {event ->
		if (event.source instanceof FileSystemResource) {
			//we need to check if the file is located in a module
			def moduleInfoMaps = getModuleDef(event.source)
			if (moduleInfoMaps) {
				//it exists in a module, so let's specifically compile only these module
				recompileGwtModule(moduleInfoMaps, event.source)
			}
		}
	}

	def onApplicationChange = {event ->
	}

	def loadGwtModules() {
		if (moduleMap) return
		print "Loading available GWT modules from the classpath..."
		def moduleFiles = resolver.getResources("classpath:**/src/gwt/**/*.gwt.xml") as List
		moduleFiles += resolver.getResources("classpath:**/src/java/**/*.gwt.xml") as List
		moduleMap = [:]
		moduleFiles.each {moduleFile ->
			def path = moduleFile.URI.toString()
			def m = path =~ /src\/(?:java|gwt)\/([\w\/]+)\.gwt\.xml$/
			if (m.count > 0) {
				def moduleName = m[0][1].replace('/' as char, '.' as char)
				try {
					def moduleDef = ModuleDefLoader.loadFromClassPath(TreeLogger.NULL, moduleName)
					LOG.info "Loaded Module: ${moduleName}"
					//only store modules with entry points
					if (moduleDef.getEntryPointTypeNames().size() > 0)
						moduleMap.put(moduleName, [module: moduleDef, lastCompileTime: 0])
				} catch (UnableToCompleteException ex) {
					LOG.warn "Unable to load module ${moduleName}", ex
				}
			}
		}
		println "Done!"
	}

	/**
	 * Checks which gwt this file belongs to.  It will search recursively up the file tree.
	 * If it can't find a module definition within the classpath, then it will return null.
	 */
	def getModuleDef(FileSystemResource resource) {
		loadGwtModules()
		def path = resource.file.absolutePath
		def m = path =~ /src\/(?:java|gwt)\/(.*)$/
		if (m.count == 0) return null
		def normalizedResource = m[0][1]

		def depModuleInfoMaps = []
		//find public resource
		for (HashMap moduleInfoMap: moduleMap.values()) {
			ModuleDef moduleDef = moduleInfoMap.module
			//this will effectively skip recompiling when multiple files are modified..
			if (path.endsWith(".java")) {
				//change our resource into a class notation
				def className = normalizedResource.replace(File.separatorChar, '.' as char) - ".java"
				//find source file
				if (moduleDef.findJavaSourceFile(className) != null) {
					//skip if our last compile time is already newer than the resource's modification time
					if (moduleInfoMap.lastCompileTime > resource.lastModified()) continue
					LOG.debug "Source file used by module ${moduleDef.name}. Setting module for recompile."
					depModuleInfoMaps << moduleInfoMap
				}
			} else {
				//subtract the module location from the resource
				def modulePath = moduleDef.name.substring(0, moduleDef.name.lastIndexOf('.')).replace('.' as char, File.separatorChar)
				def publicResource = normalizedResource - modulePath
				//ASSUMPTION: public files must all reside in a subdirectory below the module directory
				publicResource = publicResource.substring(publicResource.indexOf(File.separatorChar as String, 1) + 1)
				if (moduleDef.findPublicFile(publicResource) != null) {
					LOG.debug "Public resource used by module ${moduleDef.name}. Copying resource to module directory."
					//depModuleInfoMaps << moduleInfoMap
					//since it's a public resource, let's circumvent the recompile process and copy the resource
					//file directly to the output directory.  This will drastically increase the reload during dev
					FileUtils.copyFile(resource.file, new File("${outDir}/${moduleDef.name}/${publicResource}"))
				}
			}
		}
		return depModuleInfoMaps
	}

	/**
	 * Runs the GWTCompiler to recompile the module
	 */
	def recompileGwtModule(moduleInfoMaps, resource) {
		moduleInfoMaps.each {moduleInfoMap ->
			def module = moduleInfoMap.module
			print "Recompiling GWT module ${module.name}..."
			try {
				//refresh will update new/removed/modified/stale files
				module.refresh(TreeLogger.NULL)
				GWTCompiler compiler = new GWTCompiler()
				compiler.setStyleObfuscated()
				compiler.setOutDir(new File(outDir))
				compiler.setModuleName(module.name)
				compiler.distill(TreeLogger.NULL, module)
				println "Done!"
				moduleInfoMap.lastCompileTime = System.currentTimeMillis()
			} catch (InterruptedException ex) {
				LOG.warn "Interrupted while waiting for compiler to finish"
			} catch (UnableToCompleteException ex) {
				println "ERROR!"
				LOG.error "Unable to recompile module ${module.name}", ex
			}
		}
	}
}
