package com.byte_stefan.bytelego

import com.android.SdkConstants
import com.android.build.api.transform.*
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.ide.common.internal.WaitableExecutor
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.*
import org.objectweb.asm.commons.AdviceAdapter
import java.io.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

object Config {
    var isForSDK = false
}

class ByteLegoPlugin : Plugin<Project>, Transform() {

    override fun apply(project: Project) {
        //判断是否为sdk引用该插件
        Config.isForSDK = project.plugins.hasPlugin(LibraryPlugin::class.java)
        //加载解析配置文件
        ConfigManager.loadConfig(project)
        project.extensions.getByType(BaseExtension::class.java).registerTransform(this)
    }

    override fun getName(): String = "ByteLego"

    override fun getInputTypes(): MutableSet<QualifiedContent.ContentType> =
        TransformManager.CONTENT_JARS

    override fun isIncremental(): Boolean = true

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> = if (Config.isForSDK) {
        TransformManager.PROJECT_ONLY
    } else {
        TransformManager.SCOPE_FULL_PROJECT
    }

    override fun transform(transformInvocation: TransformInvocation) {
        super.transform(transformInvocation)

        //未配置插桩规则则直接return
        if (ConfigManager.getConfig().isNullOrEmpty()) {
            return
        }

        val outputProvider = transformInvocation.outputProvider
        val isIncremental = transformInvocation.isIncremental
        val waitableExecutor = WaitableExecutor.useGlobalSharedThreadPool()

        if (!isIncremental) {
            outputProvider.deleteAll()
        }

        transformInvocation.inputs.forEach { transformInput ->

            //处理jar包中的类
            transformInput.jarInputs.forEach { jarInput ->
                waitableExecutor.execute {
                    processJarInput(jarInput, outputProvider, isIncremental)
                }
            }

            //处理文件夹中的类
            transformInput.directoryInputs.forEach { dirInput ->
                waitableExecutor.execute {
                    processDirInput(dirInput, outputProvider, isIncremental)
                }
            }
        }
        waitableExecutor.waitForTasksWithQuickFail<Any>(true)
    }

    private fun processDirInput(
        dirInput: DirectoryInput,
        outputProvider: TransformOutputProvider,
        isIncremental: Boolean
    ) {
        val outputDirFile = outputProvider.getContentLocation(
            dirInput.name,
            dirInput.contentTypes,
            dirInput.scopes,
            Format.DIRECTORY
        )
        val inputDirPath = dirInput.file.absolutePath
        val outputDirPath = outputDirFile.absolutePath
        if (isIncremental) {
            dirInput.changedFiles.forEach { (file, status) ->
                val outputFilePath = file.absolutePath.replace(inputDirPath, outputDirPath)
                val outputFile = File(outputFilePath)
                when (status) {
                    Status.REMOVED -> FileUtils.forceDeleteOnExit(outputFile)
                    Status.ADDED, Status.CHANGED -> {
                        FileUtils.touch(outputFile)
                        transformInputFile(file, outputFile)
                    }
                }
            }
        } else {
            FileUtils.listFiles(dirInput.file, arrayOf("class"), true).forEach { inputFile ->
                //准备输出文件
                val outputFilePath = inputFile.absolutePath.replace(inputDirPath, outputDirPath)
                val outputFile = File(outputFilePath)
                FileUtils.touch(outputFile)
                transformInputFile(inputFile, outputFile)
            }
        }
    }

    private fun transformInputFile(inputFile: File, outputFile: File) {
        //处理class文件，除了R文件生产的相关文件
        if (inputFile.exists()
            && inputFile.name.endsWith(".class")
            && !inputFile.name.startsWith("R.class")
            && !inputFile.name.startsWith("R$")
            && inputFile.name != "BuildConfig.class"
        ) {

            val inputStream = FileInputStream(inputFile)
            val outputStream = FileOutputStream(outputFile)

            //ASM处理class文件
            val classReader = ClassReader(inputStream)
            val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
            classReader.accept(
                LegoClassVisitor(classWriter),
                ClassReader.EXPAND_FRAMES
            )
            outputStream.write(classWriter.toByteArray())

            inputStream.close()
            outputStream.close()

        } else {
            FileUtils.copyFile(inputFile, outputFile)
        }
    }

    private fun processJarInput(
        jarInput: JarInput,
        outputProvider: TransformOutputProvider,
        isIncremental: Boolean
    ) {
        val outputJarFile = outputProvider.getContentLocation(
            jarInput.name,
            jarInput.contentTypes,
            jarInput.scopes,
            Format.JAR
        )
        if (isIncremental){
            when(jarInput.status){
                Status.REMOVED -> FileUtils.forceDeleteOnExit(outputJarFile)
                Status.ADDED -> transformInputJar(jarInput)
                Status.CHANGED ->{
                    FileUtils.forceDeleteOnExit(outputJarFile)
                    transformInputJar(jarInput)
                }
            }
        }else{
            transformInputJar(jarInput)
        }
        FileUtils.copyFile(jarInput.file, outputJarFile)
    }

    private fun transformInputJar(jarInput: JarInput) {
        val jarFile = jarInput.file
        val jarAbsolutePath = jarFile.absolutePath
        val backUpFilePath = "${jarAbsolutePath.substring(0, jarAbsolutePath.length - 4)}-${System.currentTimeMillis()}${SdkConstants.DOT_JAR}"
        val backUpFile = File(backUpFilePath)
        jarFile.renameTo(backUpFile)
        val backUpJarFile = JarFile(backUpFilePath)
        val jos = JarOutputStream(FileOutputStream(jarFile))
        for (jarEntry in backUpJarFile.entries()) {
            val className = jarEntry.name
            if (className.endsWith(".class")
                && !className.contains("R.class")
                && !className.contains("R$")){
                val classReader = ClassReader(backUpJarFile.getInputStream(jarEntry))
                val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
                classReader.accept(
                    LegoClassVisitor(classWriter),
                    ClassReader.EXPAND_FRAMES
                )
                addZipEntry(jos, JarEntry(className), ByteArrayInputStream(classWriter.toByteArray()))
            }else {
                val inputStream = backUpJarFile.getInputStream(jarEntry)
                val newZipEntry = ZipEntry(className)
                addZipEntry(jos, newZipEntry, inputStream)
            }
        }

        with(jos) {
            flush()
            finish()
            close()
        }
        backUpJarFile.close()
        backUpFile.delete()
    }

    private fun addZipEntry(jos: JarOutputStream, zipEntry: ZipEntry, inputStream: InputStream) {
        jos.putNextEntry(zipEntry)
        val buffer = ByteArray(16384)
        var length: Int
        do {
            length = inputStream.read(buffer);
            if (length == -1) {
                break
            }
            jos.write(buffer, 0, length)
            jos.flush()
        } while (length != -1);
        inputStream.close()
        jos.closeEntry()
    }
}

class LegoClassVisitor(visitor: ClassVisitor) : ClassVisitor(Opcodes.ASM7, visitor) {

    private var hitConfigList: Map<Int, ConfigDataItem>? = null
    private lateinit var currentClassName: String

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        currentClassName = name
        hitConfigList = ConfigManager.filterClassConfig(name)
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        hitConfigList = ConfigManager.filterClassAnnotationConfig(descriptor)
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        //如果未配置class规则 or class命中的规则中有配置method规则的集合不为空，直接走LegoMethodVisitor
        //如果class未命中 or class命中了，但未配置method规则, 直接走super
        if (!hitConfigList.isNullOrEmpty() && !ConfigManager.isEmptyForMatchMethodConfig(currentClassName)) {
            val methodVisitor = cv.visitMethod(access, name, descriptor, signature, exceptions)
            return LegoMethodVisitor(api, methodVisitor, access, descriptor, currentClassName, name)
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions)
    }
}

class LegoMethodVisitor(
    api: Int,
    methodVisitor: MethodVisitor,
    access: Int,
    descriptor: String?,
    className: String,
    private val methodName: String
) : AdviceAdapter(api, methodVisitor, access, methodName, descriptor) {

    private var hitConfigMap: Map<Int, ConfigDataItem> = ConfigManager.matchedMethod(methodName)
    private var mCurrentAnnotationName: String? = ""

    private val realClassName: String = className.replace("/", ".").let {
        if (it.contains("$")) {
            it.substring(0, className.indexOf("$"))
        } else {
            it
        }
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
        mCurrentAnnotationName = descriptor
        hitConfigMap = ConfigManager.matchMethodAnnotation(descriptor)
        return super.visitAnnotation(descriptor, visible)
    }

    private fun assertCheck(insertCodeConfig: InsertCodeConfig?, ext: () -> Boolean): Boolean {
        return insertCodeConfig == null || insertCodeConfig.className.isNullOrBlank() || ext()
    }

    override fun onMethodEnter() {
        super.onMethodEnter()
        if (hitConfigMap.isNullOrEmpty()) {
            return
        }
        for ((index, config) in hitConfigMap) {
            val insertCodeConfig = config.insertCodeConfig
            if (assertCheck(insertCodeConfig) { insertCodeConfig?.onMethodBefore.isNullOrBlank() }) {
                return
            }
            val realInertClassName = insertCodeConfig?.className?.replace(".", "/")
            val label0 = Label()
            val label1 = Label()
            val label2 = Label()
            mv.visitTryCatchBlock(label0, label1, label2, "java/lang/Throwable")
            mv.visitLabel(label0)
            mv.visitInsn(Opcodes.NOP)
            mv.visitIntInsn(SIPUSH, index)
            mv.visitLdcInsn(realClassName)
            mv.visitLdcInsn(methodName)
            mv.visitLdcInsn(mCurrentAnnotationName)
            mv.visitMethodInsn(
                INVOKESTATIC,
                realInertClassName,
                insertCodeConfig!!.onMethodBefore,
                "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                false
            )
            mv.visitLabel(label1)
            val label4 = Label()
            mv.visitJumpInsn(Opcodes.GOTO, label4)
            mv.visitLabel(label2)
            mv.visitFrame(
                Opcodes.F_SAME1,
                0,
                null,
                1,
                arrayOf<Any>("java/lang/Throwable")
            )
            mv.visitVarInsn(Opcodes.ASTORE, 1)
            mv.visitVarInsn(Opcodes.ALOAD, 1)
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Throwable",
                "printStackTrace",
                "()V",
                false
            )
            mv.visitInsn(Opcodes.RETURN)
            mv.visitLabel(label4)
        }
    }

    override fun onMethodExit(opcode: Int) {
        super.onMethodExit(opcode)
        if (hitConfigMap.isNullOrEmpty()) {
            return
        }
        for ((index, config) in hitConfigMap) {
            val insertCodeConfig = config.insertCodeConfig
            if (assertCheck(insertCodeConfig) { insertCodeConfig?.onMethodAfter.isNullOrBlank() }) {
                return
            }
            val realInertClassName = insertCodeConfig?.className?.replace(".", "/")
            if (opcode == Opcodes.RETURN || opcode == Opcodes.ATHROW) {
                mv.visitIntInsn(SIPUSH, index)
                mv.visitLdcInsn(realClassName)
                mv.visitLdcInsn(methodName)
                mv.visitLdcInsn(mCurrentAnnotationName)
                mv.visitMethodInsn(
                    INVOKESTATIC,
                    realInertClassName,
                    insertCodeConfig!!.onMethodAfter,
                    "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                    false
                )
            }
        }
    }
}