
import EAuthState.*
import EUpdate.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.Log
import org.drinkless.tdlib.TdApi
import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

open class MClient {

    private val quiting = AtomicBoolean(false)
    private val users = ConcurrentHashMap<Int, TdApi.User>()
    private val basicGroups = ConcurrentHashMap<Int, TdApi.BasicGroup>()
    private val supergroups = ConcurrentHashMap<Int, TdApi.Supergroup>()
    private val secretChats = ConcurrentHashMap<Int, TdApi.SecretChat>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val chatList = TreeSet<OrderedChat>()
    private val usersFullInfo = ConcurrentHashMap<Int, TdApi.UserFullInfo>()
    private val basicGroupsFullInfo = ConcurrentHashMap<Int, TdApi.BasicGroupFullInfo>()
    private val supergroupsFullInfo = ConcurrentHashMap<Int, TdApi.SupergroupFullInfo>()

    private var client: Client? = null
    private val util = Util()
    private val auth = Auth()
    private val authHandler = AuthHandler()
    private val updateHandler = UpdateHandler()

    private inner class Util {
        fun setChatOrder(chat: TdApi.Chat, order: Long) {
            synchronized(chatList) {
                if (chat.order != 0L) {
                    val orderedChat = OrderedChat(chat.order, chat.id)
                    assert(chatList.remove(orderedChat))
                }
                chat.order = order
                if (chat.order != 0L) {
                    val orderedChat = OrderedChat(chat.order, chat.id)
                    assert(chatList.add(orderedChat))
                }
            }
        }
    }

    private inner class Auth {
        private val haveAuth = AtomicBoolean(false)
        private val authLock = ReentrantLock()
        private val gotAuth = authLock.newCondition()
        private var authState: EAuthState? = null

        fun onAuthorizationStateUpdated(authStateObj: TdApi.AuthorizationState?) {
            if (authStateObj != null) {
                authState = authStateMap[authStateObj.constructor]
            }
            outDbg("<< AuthState", authState)
            when (authState) {
                WaitTdlibParameters -> {
                    val parameters = TdApi.TdlibParameters().also {
                        with(it) {
                            databaseDirectory = "tdlib"
                            useMessageDatabase = true
                            useSecretChats = true
                            apiId = 94575
                            apiHash = "a3406de8d171bb422bb6ddf3bbd800e2"
                            systemLanguageCode = "en"
                            deviceModel = "Air"
                            systemVersion = "Unknown"
                            applicationVersion = "1.0"
                            enableStorageOptimizer = true
                        }
                    }
                    send(TdApi.SetTdlibParameters(parameters), authHandler)
                }
                WaitEncryptionKey -> {
                    send(TdApi.CheckDatabaseEncryptionKey(), authHandler)
                }
                WaitPhoneNumber -> {
                    val phoneNumber = promptString("Please enter phone number: ")
                    send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, false, false), authHandler)
                }
                WaitCode -> {
                    val code = promptString("Please enter authentication code: ")
                    send(TdApi.CheckAuthenticationCode(code, "", ""), authHandler)
                }
                WaitPassword -> {
                    val password = promptString("Please enter password: ")
                    send(TdApi.CheckAuthenticationPassword(password), authHandler)
                }
                Ready -> {
                    haveAuth.set(true)
                    authLock.withLock {
                        gotAuth.signal()
                    }
                }
                LoggingOut -> {
                    haveAuth.set(false)
                    outDbg("Logging out")
                }
                Closing -> {
                    haveAuth.set(false)
                    print("Closing")
                }
                Closed -> {
                    print("Closed")
                    if (!quiting.get()) {
                        client = Client.create(updateHandler, null, null)
                    }
                }
                else -> {
                    System.err.println("Unsupported authorization state:\n$authState")
                }
            }
        }
    }

    private inner class AuthHandler: Client.ResultHandler {
        override fun onResult(o: TdApi.Object) {
            when (o.constructor) {
                TdApi.Error.CONSTRUCTOR -> {
                    System.err.println("Receive an error:\n$o")
                    auth.onAuthorizationStateUpdated(null) // repeat last action
                }
                TdApi.Ok.CONSTRUCTOR -> {
                    // result is already received through UpdateAuthorizationState, nothing to do
                }
                else -> {
                    System.err.println("Receive wrong response from TDLib:\n$o")
                }
            }
        }
    }

    private inner class UpdateHandler: Client.ResultHandler {
        override fun onResult(updObj: TdApi.Object?) {
            val updType = updateMap[updObj?.constructor]
            println("<< Update $updType")
            when (updType) {
                UpdateAuthorizationState -> {
                    val state = updObj as TdApi.UpdateAuthorizationState
                    auth.onAuthorizationStateUpdated(state.authorizationState)
                }
                UpdateUser -> {
                    val updateUser = updObj as TdApi.UpdateUser
                    users[updateUser.user.id] = updateUser.user
                    with(updateUser.user) {
                        outDbg("<< UpdateUser", "$firstName $lastName ($username) ($phoneNumber)")
                    }
                }
                UpdateUserStatus -> {
                    val updateUserStatus = updObj as TdApi.UpdateUserStatus
                    users[updateUserStatus.userId]?.let {
                        synchronized(users) {
                            it.status = updateUserStatus.status
                        }
                        outDbg("<< UpdateUserStatus", "${it.firstName} ${it.lastName} " +
                                "(${it.username}) (${it.phoneNumber}): ${updateUserStatus.status}")
                    }
                }
                UpdateBasicGroup -> {
                    val updateBasicGroup = updObj as TdApi.UpdateBasicGroup
                    basicGroups[updateBasicGroup.basicGroup.id] = updateBasicGroup.basicGroup
                }
                UpdateSupergroup -> {
                    val updateSupergroup = updObj as TdApi.UpdateSupergroup
                    supergroups[updateSupergroup.supergroup.id] = updateSupergroup.supergroup
                    outDbg("<< UpdateSupergroup", updateSupergroup)
                }
                UpdateSecretChat -> {
                    val updateSecretChat = updObj as TdApi.UpdateSecretChat
                    secretChats[updateSecretChat.secretChat.id] = updateSecretChat.secretChat
                }
                UpdateNewChat -> {
                    val updateNewChat = updObj as TdApi.UpdateNewChat
                    val chat = updateNewChat.chat
                    synchronized(chats) {
                        chats[chat.id] = chat
                        val order = chat.order
                        chat.order = 0
                        util.setChatOrder(chat, order)
                    }
                    outDbg("<< UpdateNewChat", updateNewChat)
                }
                UpdateChatTitle -> {
                    val updateChat = updObj as TdApi.UpdateChatTitle
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.title = updateChat.title
                        }
                    }
                }
                UpdateChatPhoto -> {
                    val updateChat = updObj as TdApi.UpdateChatPhoto
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.photo = updateChat.photo
                        }
                    }
                }
                UpdateChatLastMessage -> {
                    val updateChat = updObj as TdApi.UpdateChatLastMessage
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.lastMessage = updateChat.lastMessage
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateChatOrder -> {
                    val updateChat = updObj as TdApi.UpdateChatOrder
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateChatIsPinned -> {
                    val updateChat = updObj as TdApi.UpdateChatIsPinned
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.isPinned = updateChat.isPinned
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateChatReadInbox -> {
                    val updateChat = updObj as TdApi.UpdateChatReadInbox
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.lastReadInboxMessageId = updateChat.lastReadInboxMessageId
                            it.unreadCount = updateChat.unreadCount
                        }
                    }
                }
                UpdateChatReadOutbox -> {
                    val updateChat = updObj as TdApi.UpdateChatReadOutbox
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId
                        }
                    }
                }
                UpdateChatUnreadMentionCount -> {
                    val updateChat = updObj as TdApi.UpdateChatUnreadMentionCount
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.unreadMentionCount = updateChat.unreadMentionCount
                        }
                    }
                }
                UpdateMessageMentionRead -> {
                    val updateChat = updObj as TdApi.UpdateMessageMentionRead
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.unreadMentionCount = updateChat.unreadMentionCount
                        }
                    }
                }
                UpdateChatReplyMarkup -> {
                    val updateChat = updObj as TdApi.UpdateChatReplyMarkup
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.replyMarkupMessageId = updateChat.replyMarkupMessageId
                        }
                    }
                }
                UpdateChatDraftMessage -> {
                    val updateChat = updObj as TdApi.UpdateChatDraftMessage
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.draftMessage = updateChat.draftMessage
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateNotificationSettings -> {
                    val update = updObj as TdApi.UpdateNotificationSettings
                    val scope = update.scope
                    if (scope is TdApi.NotificationSettingsScopeChat) {
                        chats[scope.chatId]?.let {
                            synchronized(it) {
                                it.notificationSettings = update.notificationSettings
                            }
                        }
                    }
                    outDbg("<< UpdateNotificationSettings", "$scope: ${update.notificationSettings}")
                }
                UpdateUserFullInfo -> {
                    val updateUserFullInfo = updObj as TdApi.UpdateUserFullInfo
                    usersFullInfo[updateUserFullInfo.userId] = updateUserFullInfo.userFullInfo
                }
                UpdateBasicGroupFullInfo -> {
                    val updateBasicGroupFullInfo = updObj as TdApi.UpdateBasicGroupFullInfo
                    basicGroupsFullInfo[updateBasicGroupFullInfo.basicGroupId] = updateBasicGroupFullInfo.basicGroupFullInfo
                }
                UpdateSupergroupFullInfo -> {
                    val updateSupergroupFullInfo = updObj as TdApi.UpdateSupergroupFullInfo
                    supergroupsFullInfo[updateSupergroupFullInfo.supergroupId] = updateSupergroupFullInfo.supergroupFullInfo
                }
                UpdateOption -> {
                    val updateOption = updObj as TdApi.UpdateOption
                    outDbg("<< UpdateOption", "${updateOption.name} = ${updateOption.value}")
                }
                UpdateConnectionState -> {
                    val updateConState = updObj as TdApi.UpdateConnectionState
                    outDbg("<< UpdateConnectionState", updateConState)
                }
                UpdateNewMessage -> {
                    val updateNewMessage = updObj as TdApi.UpdateNewMessage
                    outDbg("<< UpdateNewMessage", updateNewMessage.message)
                }
                UpdateDeleteMessages -> {
                    val updateDelMessage = updObj as TdApi.UpdateDeleteMessages
                    outDbg("<< UpdateDeleteMessages", updateDelMessage)
                    TdApi.GetMessage()
                }
            }
        }
    }

    private inner class ExceptionHandler: Client.ExceptionHandler {
        override fun onException(t: Throwable?) {
            t?.printStackTrace(System.err)
        }
    }

    private fun send(query: TdApi.Function, resHandler: Client.ResultHandler) {
        val funType = functionMap[query.constructor]
        outDbg(">> Send: $funType")
        client?.send(query, resHandler)
    }

    private fun promptString(prompt: String): String {
        print(prompt)
        BufferedReader(InputStreamReader(System.`in`)).use {
            return it.readLine()
        }
    }

    private val outDbgFile = OutputStreamWriter(FileOutputStream("outDbgFile.log", false))

    private fun outDbg(prefix: String, msg: Any? = "") {
        val msgClean = msg?.toString()?.replace("\n", "")?.replace("  ", " ")
        outDbgFile.appendln("$prefix: $msgClean")
        outDbgFile.flush()
    }

    private class OrderedChat (private val order: Long, private val chatId: Long): Comparable<OrderedChat> {
        override fun compareTo(o: OrderedChat): Int {
            if (this.order != o.order) {
                return if (o.order < this.order) -1 else 1
            }
            if (this.chatId != o.chatId) {
                return if (o.chatId < this.chatId) -1 else 1
            }
            return 0
        }
        override fun equals(obj: Any?): Boolean {
            val o = obj as OrderedChat?
            return this.order == o!!.order && this.chatId == o.chatId
        }
    }

    private fun initEnv() {
        System.loadLibrary("tdjni")
        Log.setVerbosityLevel(6)
        if (!Log.setFilePath("tdlib.log"))
            throw IOError(IOException("Write access to current dir is required"))
    }

    private fun initClient() {
        client = Client.create(updateHandler, ExceptionHandler(), ExceptionHandler())
    }

    fun init() {
        initEnv()
        initClient()
    }

    fun test() {
        val query = TdApi.GetTextEntities("@telegram /test_command https://telegram.org telegram.me @gif @test")
        Client.execute(query)
    }
}

fun main(args: Array<String>) {
    val inst = MClient()
    inst.init()
    inst.test()
}