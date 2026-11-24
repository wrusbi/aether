package dev.aetherlab.aether

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import dev.aetherlab.aether.data.AgentExtensionsRepository
import dev.aetherlab.aether.data.AlpineChromeController
import dev.aetherlab.aether.data.AgentModeController
import dev.aetherlab.aether.data.AgentPluginSkillStore
import dev.aetherlab.aether.data.AgentSkillManager
import dev.aetherlab.aether.data.AetherAppExtensionManager
import dev.aetherlab.aether.data.AetherModKernel
import dev.aetherlab.aether.data.AetherDiagnosticLogger
import dev.aetherlab.aether.data.AetherToolExecutor
import dev.aetherlab.aether.data.ChatRepository
import dev.aetherlab.aether.data.DeviceRootController
import dev.aetherlab.aether.data.PiExtensionManager
import dev.aetherlab.aether.data.PiExtensionStateRepository
import dev.aetherlab.aether.data.PluginVault
import dev.aetherlab.aether.data.RootSetupController
import dev.aetherlab.aether.data.RuntimeWorkspaceFileBridge
import dev.aetherlab.aether.data.ChatStateStore
import dev.aetherlab.aether.data.ScheduledTask
import dev.aetherlab.aether.data.ScheduledTaskManager
import dev.aetherlab.aether.data.ScheduledTaskRepository
import dev.aetherlab.aether.data.ScheduledTaskScheduler
import dev.aetherlab.aether.data.SessionExecutionManager
import dev.aetherlab.aether.data.SettingsRepository
import dev.aetherlab.aether.data.WorkspaceFileBridge
import dev.aetherlab.aether.data.pi.PiCompletionClient
import dev.aetherlab.aether.data.pi.PiAgentRunner
import dev.aetherlab.aether.data.pi.PiKernelBridge
import dev.aetherlab.aether.mod.AetherNativeModManager
import dev.aetherlab.aether.runtime.AlpineRuntime
import dev.aetherlab.aether.runtime.RuntimeRouter
import dev.aetherlab.aether.runtime.RuntimeShellTool
import dev.aetherlab.aether.runtime.TermuxRuntime
import dev.aetherlab.aether.termux.TermuxBashTool
import dev.aetherlab.aether.termux.TermuxRuntimeOperations
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AetherApplication : Application() {
    val runtime: AetherAppRuntime by lazy(LazyThreadSafetyMode.NONE) {
        AetherAppRuntime(this)
    }
    @Volatile
    private var isPostHogInitialized = false

    override fun onCreate() {
        super.onCreate()
        runtime.initialize()
    }

    override fun onTerminate() {
        runtime.nativeModManager.shutdown()
        super.onTerminate()
    }

    fun initializePostHog() {
        if (isPostHogInitialized || BuildConfig.POSTHOG_API_KEY.isBlank()) return
        synchronized(this) {
            if (isPostHogInitialized || BuildConfig.POSTHOG_API_KEY.isBlank()) return
            val config = PostHogAndroidConfig(
                apiKey = BuildConfig.POSTHOG_API_KEY,
                host = BuildConfig.POSTHOG_HOST,
            ).apply {
                captureApplicationLifecycleEvents = true
                captureDeepLinks = true
                captureScreenViews = true
                debug = BuildConfig.DEBUG
                releaseIdentifier = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                errorTrackingConfig.autoCapture = true
                errorTrackingConfig.inAppIncludes.addAll(
                    listOf(
                        "dev.aetherlab.aether",
                        "dev.aetherlab.aether",
                    ),
                )
            }
            PostHogAndroid.setup(this, config)
            isPostHogInitialized = true
        }
    }
}

class AetherAppRuntime(
    private val application: AetherApplication,
) {
    val diagnosticLogger = AetherDiagnosticLogger(application)
    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                diagnosticLogger.exception(
                    category = "coroutine",
                    event = "uncaught_exception",
                    throwable = throwable,
                )
            }
    )

    val settingsRepository = SettingsRepository(application)
    val piExtensionStateRepository = PiExtensionStateRepository(application)
    val modKernel = AetherModKernel()
    val chatRepository = ChatRepository(application)
    val extensionsRepository = AgentExtensionsRepository(application)
    val scheduledTaskRepository = ScheduledTaskRepository(application)
    val bashTool = TermuxBashTool(
        context = application,
        diagnosticLogger = diagnosticLogger,
    )
    val termuxRuntime = TermuxRuntime(bashTool)
    val alpineRuntime = AlpineRuntime(
        context = application,
        diagnosticLogger = diagnosticLogger,
    )
    val piKernelBridge = PiKernelBridge(
        alpineRuntime = alpineRuntime,
        diagnosticLogger = diagnosticLogger,
    )
    val nativeModManager = AetherNativeModManager(
        context = application,
        application = application,
        alpineRuntime = alpineRuntime,
        piKernelBridge = piKernelBridge,
        kernel = modKernel,
        piExtensionStateRepository = piExtensionStateRepository,
        diagnosticLogger = diagnosticLogger,
    )
    val piCompletionClient = PiCompletionClient(
        bridge = piKernelBridge,
        settingsRepository = settingsRepository,
    )
    val runtimeRouter = RuntimeRouter(
        termuxRuntime = termuxRuntime,
        alpineRuntime = alpineRuntime,
    )
    val rootSetupController = RootSetupController(
        context = application,
        bashTool = bashTool,
        diagnosticLogger = diagnosticLogger,
    )
    val deviceRootController = DeviceRootController(
        context = application,
        diagnosticLogger = diagnosticLogger,
        chrootRootProvider = { alpineRuntime.chrootRootPath() },
        onAllowlistEntryApproved = { entry ->
            settingsRepository.addRootCommandAllowlistEntry(entry)
        },
    )
    val workspaceFileBridge = WorkspaceFileBridge(
        context = application,
        bashTool = bashTool,
    )
    val runtimeWorkspaceFileBridge = RuntimeWorkspaceFileBridge(
        context = application,
        runtimeRouter = runtimeRouter,
        alpineRuntime = alpineRuntime,
        termuxFileBridge = workspaceFileBridge,
    )
    val agentModeController = AgentModeController(
        context = application,
        bashTool = bashTool,
        runtimeWorkspaceFileBridge = runtimeWorkspaceFileBridge,
        diagnosticLogger = diagnosticLogger,
    )
    val alpineChromeController = AlpineChromeController(
        context = application,
        alpineRuntime = alpineRuntime,
        diagnosticLogger = diagnosticLogger,
    )
    val skillManager = AgentSkillManager(
        context = application,
        extensionsRepository = extensionsRepository,
    )
    val pluginVault = PluginVault(
        rootDirectory = File(application.filesDir, "plugins"),
        skills = AgentPluginSkillStore(skillManager, extensionsRepository),
    )
    val piExtensionManager = PiExtensionManager(
        context = application,
        alpineRuntime = alpineRuntime,
        piKernelBridge = piKernelBridge,
        skillManager = skillManager,
        stateRepository = piExtensionStateRepository,
    )
    val aetherAppExtensionManager = AetherAppExtensionManager(
        bridge = piKernelBridge,
        scope = appScope,
        diagnosticLogger = diagnosticLogger,
        modKernel = modKernel,
        loadOptionsProvider = piExtensionStateRepository::loadOptions,
    )
    val piAgentRunner = PiAgentRunner(
        bridge = piKernelBridge,
        settingsRepository = settingsRepository,
        piExtensionStateRepository = piExtensionStateRepository,
        appExtensionManager = aetherAppExtensionManager,
        alpineChromeController = alpineChromeController,
        termuxRuntimeOperations = TermuxRuntimeOperations(bashTool),
        diagnosticLogger = diagnosticLogger,
        toolExecutor = AetherToolExecutor(
            runtimeRouter = runtimeRouter,
            agentModeController = agentModeController,
            deviceRootController = deviceRootController,
            runtimeShellTool = RuntimeShellTool(runtimeRouter),
        ),
    )
    val appForegroundTracker = AppForegroundTracker()
    val notificationController = AetherNotificationController(application)
    val scheduledTaskScheduler = ScheduledTaskScheduler(
        context = application,
        diagnosticLogger = diagnosticLogger,
    )
    val scheduledTaskManager = ScheduledTaskManager(
        repository = scheduledTaskRepository,
        scheduler = scheduledTaskScheduler,
    )
    val chatStateStore = ChatStateStore(
        scope = appScope,
        chatRepository = chatRepository,
    )
    val sessionExecutionManager = SessionExecutionManager(
        application = application,
        scope = appScope,
        settingsRepository = settingsRepository,
        extensionsRepository = extensionsRepository,
        chatStateStore = chatStateStore,
        chatRepository = chatRepository,
        bashTool = bashTool,
        runtimeRouter = runtimeRouter,
        workspaceFileBridge = workspaceFileBridge,
        rootSetupController = rootSetupController,
        agentModeController = agentModeController,
        skillManager = skillManager,
        piExtensionManager = piExtensionManager,
        scheduledTaskManager = scheduledTaskManager,
        notificationController = notificationController,
        appForegroundTracker = appForegroundTracker,
        diagnosticLogger = diagnosticLogger,
        piCompletionClient = piCompletionClient,
        piKernelBridge = piKernelBridge,
        piAgentRunner = piAgentRunner,
    )

    fun initialize() {
        if (BuildConfig.SHOWCASE_MODE) {
            appScope.launch {
                val current = settingsRepository.settings.first()
                settingsRepository.replaceImportedSettings(
                    settings = current.copy(
                        onboardingSeenVersion = dev.aetherlab.aether.data.CurrentOnboardingVersion,
                        onboardingCompletedVersion = dev.aetherlab.aether.data.CurrentOnboardingVersion,
                        defaultChatModelKey = "showcase-openai::gpt-6-astra",
                        modelId = "gpt-6-astra", reasoningEffort = "low",
                        keepTasksRunningInBackground = false, notifyOnTaskCompletion = false,
                    ),
                    providerConfigs = dev.aetherlab.aether.data.ShowcaseCatalog.providers(),
                )
            }
            return
        }
        diagnosticLogger.installUncaughtExceptionHandler()
        diagnosticLogger.event(
            category = "app",
            event = "startup",
            details = mapOf(
                "version_name" to BuildConfig.VERSION_NAME,
                "version_code" to BuildConfig.VERSION_CODE,
                "debug" to BuildConfig.DEBUG,
            ),
        )
        notificationController.ensureChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appForegroundTracker)
        appScope.launch {
            nativeModManager.initialize()
        }
        appScope.launch {
            alpineRuntime.refreshApkRepositoriesForCurrentNetwork()
        }
        appScope.launch {
            settingsRepository.migrateLegacyProvidersToPi()
        }
        appScope.launch {
            settingsRepository.repairBuiltInSkillSelectionDefaults()
            skillManager.syncBuiltInSkills().onFailure { throwable ->
                diagnosticLogger.exception(
                    category = "skills",
                    event = "builtin_sync_failed",
                    throwable = throwable,
                )
            }
        }
        appScope.launch {
            // Plugins whose files changed since install are switched off and flagged.
            runCatching { pluginVault.verifyAll() }
                .onSuccess { quarantined ->
                    quarantined.forEach { plugin ->
                        diagnosticLogger.event(
                            category = "plugins",
                            event = "plugin_quarantined",
                            level = "warn",
                            details = mapOf("plugin_id" to plugin.id),
                        )
                    }
                }
                .onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "plugins",
                        event = "plugin_verify_failed",
                        throwable = throwable,
                    )
                }
        }
        appScope.launch {
            if (settingsRepository.settings.first().privacyPolicyAccepted) {
                initializePostHog()
            }
        }
        appScope.launch {
            scheduledTaskManager.rescheduleAll()
        }
        appScope.launch {
            runCatching { piExtensionStateRepository.enableSubagentsDefaultOnce() }
                .onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "pi_extensions",
                        event = "subagents_default_migration_failed",
                        throwable = throwable,
                    )
                }
        }
    }

    fun initializePostHog() {
        application.initializePostHog()
    }

    fun handleScheduledTaskAlarm(
        taskId: String,
        pendingResult: android.content.BroadcastReceiver.PendingResult,
    ) {
        diagnosticLogger.event(
            category = "scheduled_task",
            event = "alarm_received",
            details = mapOf("task_id" to taskId),
        )
        appScope.launch {
            try {
                val task = scheduledTaskManager.markTriggeredAndScheduleNext(taskId)
                if (task == null) {
                    diagnosticLogger.event(
                        category = "scheduled_task",
                        event = "trigger_missing_task",
                        level = "warn",
                        details = mapOf("task_id" to taskId),
                    )
                    return@launch
                }
                val started = startScheduledTaskFromAlarm(task)
                diagnosticLogger.event(
                    category = "scheduled_task",
                    event = if (started) "trigger_started" else "trigger_skipped",
                    level = if (started) "info" else "warn",
                    details = mapOf("task_id" to taskId),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun startScheduledTaskFromAlarm(task: ScheduledTask): Boolean {
        return try {
            if (settingsRepository.settings.first().keepTasksRunningInBackground) {
                runCatching {
                    AetherForegroundService.ensureRunning(application)
                }.onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "scheduled_task",
                        event = "foreground_service_start_failed",
                        throwable = throwable,
                        details = mapOf("task_id" to task.id),
                    )
                }
            }
            sessionExecutionManager.startScheduledTask(task)
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "scheduled_task",
                event = "trigger_start_failed",
                throwable = throwable,
                details = mapOf("task_id" to task.id),
            )
            false
        }
    }

    fun rescheduleScheduledTasks(
        pendingResult: android.content.BroadcastReceiver.PendingResult,
    ) {
        appScope.launch {
            try {
                scheduledTaskManager.rescheduleAll()
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class AppForegroundTracker : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(false)

    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}

val Context.aetherRuntime: AetherAppRuntime
    get() = (applicationContext as AetherApplication).runtime
