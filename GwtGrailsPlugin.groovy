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
	//default to pretty output style for development purposes
	def outputStyle = System.getProperty("gwt.output.style", "PRETTY").toUpperCase()

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
			processResourceForModules(event.source)
		}
	}

	def onApplicationChange = {event ->
	}

	/** finds and loads gwt modules in our classpath     */
	def loadGwtModules() {
		if (moduleMap) return
		print "Loading available GWT modules from the classpath..."
		def moduleFileResources = resolver.getResources("classpath:**/src/gwt/**/*.gwt.xml") as List
		moduleFileResources += resolver.getResources("classpath:**/src/java/**/*.gwt.xml") as List
		moduleMap = [:]
		moduleFileResources.each {moduleFileResource ->
			def path = moduleFileResource.URI.toString()
			def m = path =~ /src\/(?:java|gwt)\/([\w\/]+)\.gwt\.xml$/
			if (m.count > 0) {
				def moduleName = m[0][1].replace('/' as char, '.' as char)
				try {
					LOG.debug "Loading Module: ${moduleName}"
					def moduleDef = ModuleDefLoader.loadFromClassPath(TreeLogger.NULL, moduleName)
					def runnable = moduleDef.getEntryPointTypeNames().size() > 0 ? true : false
					def depModules = getInheritedModules(moduleFileResource.file)
					moduleMap.put(moduleName, [module: moduleDef, lastCompileTime: 0, runnable: runnable, depModules: depModules])
					LOG.info "Loaded Module: ${moduleName}"
				} catch (UnableToCompleteException ex) {
					LOG.warn "Unable to load module ${moduleName}", ex
				}
			}
		}
		println "Done!"
	}

	/** parse the gwt file to retrieve the inherited modules     */
	def getInheritedModules(File modulePath) {
		def depModules = []
		modulePath.eachLine {line ->
			def m = line =~ /<inherits\s+name=["']([\w\.]*)["']/
			if (m.count > 0) {
				depModules << m[0][1]
			}
		}
		if (depModules) LOG.debug "Inherits Modules: " + depModules.join(',')
		return depModules
	}

	/**
	 * Checks which gwt module this file belongs to.
	 */
	def processResourceForModules(FileSystemResource resource) {
		loadGwtModules()
		def path = resource.file.absolutePath
		def m = path =~ /src\/(?:java|gwt)\/(.*)$/
		if (m.count == 0) return null
		def normalizedResource = m[0][1]

		def affectedModuleInfoMap = null
		def publicResource = null
		//ASSUMPTION: one resource belongs to one and only one module
		if (path.endsWith(".gwt.xml")) {
			//process gwt.xml by reloading the entire module
			def fileModuleName = normalizedResource.replace(File.separatorChar, '.' as char) - ".gwt.xml"
			LOG.debug "gwt.xml file changed for module ${fileModuleName}."
			affectedModuleInfoMap = moduleMap.get(fileModuleName)
			//refresh module info
			affectedModuleInfoMap.module.refresh(TreeLogger.NULL)
			affectedModuleInfoMap.depModules = getInheritedModules(resource.file)
		} else {
			for (HashMap moduleInfoMap: moduleMap.values()) {
				ModuleDef moduleDef = moduleInfoMap.module
				if (path.endsWith(".java")) {
					//change our resource into a class notation
					def className = normalizedResource.replace(File.separatorChar, '.' as char) - ".java"
					//find source file
					moduleDef.metaClass.findJavaSourceFile = {partialPath -> return findSourceFile(partialPath)}
					if (moduleDef.findJavaSourceFile(className) != null) {
						//skip if our last compile time is already newer than the resource's modification time
						if (moduleInfoMap.lastCompileTime < resource.lastModified()) {
							LOG.debug "Source file used by module ${moduleDef.name}."
							affectedModuleInfoMap = moduleInfoMap
						}
						break
					}
				} else {
					//subtract the module location from the resource
					def modulePath = moduleDef.name.substring(0, moduleDef.name.lastIndexOf('.')).replace('.' as char, File.separatorChar)
					publicResource = normalizedResource - modulePath
					//ASSUMPTION: public files must all reside in a subdirectory below the module directory
					publicResource = publicResource.substring(publicResource.indexOf(File.separatorChar as String, 1) + 1)
					if (moduleDef.findPublicFile(publicResource) != null) {
						LOG.debug "Public resource used by module ${moduleDef.name}."
						affectedModuleInfoMap = moduleInfoMap
						break;
					}
				}
			}
		}

		if (affectedModuleInfoMap) {
			//check which modules inherit this affected module
			def depModuleInfoMaps = moduleMap.values().findAll {if (it.depModules.contains(affectedModuleInfoMap.module.name)) return true}
			depModuleInfoMaps << affectedModuleInfoMap
			LOG.debug "Dependent module info maps: ${depModuleInfoMaps}"
			//process all dependent modules
			for (HashMap moduleInfoMap: depModuleInfoMaps) {
				def moduleDef = moduleInfoMap.module
				//ASSUMPTION: affected module should not have any dependent modules
				//only recompile modules that has entry points
				if (moduleInfoMap.runnable) {
					if (publicResource) {
						LOG.debug "Copying public resource ${publicResource} to dependent module ${moduleDef.name}."
						//since it's a public resource, let's circumvent the recompile process and copy the resource
						//file directly to the output directory.  This will drastically increase the reload during dev
						FileUtils.copyFile(resource.file, new File("${outDir}/${moduleDef.name}/${publicResource}"))
					} else {
						recompileGwtModule(moduleInfoMap, resource)
					}
				}
			}
		}
	}

	/**
	 * Runs the GWTCompiler to recompile the module
	 */
	def recompileGwtModule(HashMap moduleInfoMap, FileSystemResource resource) {
		def module = moduleInfoMap.module
		if (moduleInfoMap.lastCompileTime >= resource.lastModified()) return
		print "Recompiling GWT module ${module.name}..."
		try {
			//refresh will update new/removed/modified/stale files
			module.refresh(TreeLogger.NULL)
			GWTCompiler compiler = new GWTCompiler()
			switch (outputStyle) {
				case "OBF":
					compiler.setStyleObfuscated()
					break
				case "PRETTY":
					compiler.setStylePretty()
					break
				case "DETAILED":
					compiler.setStyleDetailed()
					break
			}
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
