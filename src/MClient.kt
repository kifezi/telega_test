
import EAuthState.*
import EUpdate.*
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.Log
import org.drinkless.tdlib.TdApi
import java.io.BufferedReader
import java.io.IOError
import java.io.IOException
import java.io.InputStreamReader
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
    // ------- variables -------
    private var client: Client? = null
    // -------
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

        fun onAuthorizationStateUpdated(authState2: TdApi.AuthorizationState?) {
            if (authState2 != null) {
                authState = authStateMap[authState2.constructor]
            }
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
                            deviceModel = "Desktop"
                            systemVersion = "Unknown"
                            applicationVersion = "1.0"
                            enableStorageOptimizer = true
                        }
                    }
                    send(TdApi.SetTdlibParameters(parameters), authHandler)
                }
                WaitEncryptionKey -> {
                    client?.send(TdApi.CheckDatabaseEncryptionKey(), authHandler)
                }
                WaitPhoneNumber -> {
                    val phoneNumber = promptString("Please enter phone number: ")
                    client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, false, false), authHandler)
                }
                WaitCode -> {
                    val code = promptString("Please enter authentication code: ")
                    client?.send(TdApi.CheckAuthenticationCode(code, "", ""), authHandler)
                }
                WaitPassword -> {
                    val password = promptString("Please enter password: ")
                    client?.send(TdApi.CheckAuthenticationPassword(password), authHandler)
                }
                Ready -> {
                    haveAuth.set(true)
                    authLock.withLock {
                        gotAuth.signal()
                    }
                }
                LoggingOut -> {
                    haveAuth.set(false)
                    println("Logging out")
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
        override fun onResult(resObj: TdApi.Object?) {

            when (updateMap[resObj?.constructor]) {
                UpdateAuthorizationState -> {
                    val state = resObj as TdApi.UpdateAuthorizationState
                    auth.onAuthorizationStateUpdated(state.authorizationState)
                }
                UpdateUser -> {
                    val updateUser = resObj as TdApi.UpdateUser
                    users[updateUser.user.id] = updateUser.user
                }
                UpdateUserStatus -> {
                    val updateUserStatus = resObj as TdApi.UpdateUserStatus
                    users[updateUserStatus.userId]?.let {
                        synchronized(users) {
                            it.status = updateUserStatus.status
                        }
                    }
                }
                UpdateBasicGroup -> {
                    val updateBasicGroup = resObj as TdApi.UpdateBasicGroup
                    basicGroups[updateBasicGroup.basicGroup.id] = updateBasicGroup.basicGroup
                }
                UpdateSupergroup -> {
                    val updateSupergroup = resObj as TdApi.UpdateSupergroup
                    supergroups[updateSupergroup.supergroup.id] = updateSupergroup.supergroup
                }
                UpdateSecretChat -> {
                    val updateSecretChat = resObj as TdApi.UpdateSecretChat
                    secretChats[updateSecretChat.secretChat.id] = updateSecretChat.secretChat
                }
                UpdateNewChat -> {
                    val updateNewChat = resObj as TdApi.UpdateNewChat
                    val chat = updateNewChat.chat
                    synchronized(chat) {
                        chats[chat.id] = chat
                        val order = chat.order
                        chat.order = 0
                        util.setChatOrder(chat, order)
                    }
                }
                UpdateChatTitle -> {
                    val updateChat = resObj as TdApi.UpdateChatTitle
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.title = updateChat.title
                        }
                    }
                }
                UpdateChatPhoto -> {
                    val updateChat = resObj as TdApi.UpdateChatPhoto
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.photo = updateChat.photo
                        }
                    }
                }
                UpdateChatLastMessage -> {
                    val updateChat = resObj as TdApi.UpdateChatLastMessage
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.lastMessage = updateChat.lastMessage
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateChatOrder -> {
                    val updateChat = resObj as TdApi.UpdateChatOrder
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateChatIsPinned -> {
                    val updateChat = resObj as TdApi.UpdateChatIsPinned
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.isPinned = updateChat.isPinned
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateChatReadInbox -> {
                    val updateChat = resObj as TdApi.UpdateChatReadInbox
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.lastReadInboxMessageId = updateChat.lastReadInboxMessageId
                            it.unreadCount = updateChat.unreadCount
                        }
                    }
                }
                UpdateChatReadOutbox -> {
                    val updateChat = resObj as TdApi.UpdateChatReadOutbox
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId
                        }
                    }
                }
                UpdateChatUnreadMentionCount -> {
                    val updateChat = resObj as TdApi.UpdateChatUnreadMentionCount
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.unreadMentionCount = updateChat.unreadMentionCount
                        }
                    }
                }
                UpdateMessageMentionRead -> {
                    val updateChat = resObj as TdApi.UpdateMessageMentionRead
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.unreadMentionCount = updateChat.unreadMentionCount
                        }
                    }
                }
                UpdateChatReplyMarkup -> {
                    val updateChat = resObj as TdApi.UpdateChatReplyMarkup
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.replyMarkupMessageId = updateChat.replyMarkupMessageId
                        }
                    }
                }
                UpdateChatDraftMessage -> {
                    val updateChat = resObj as TdApi.UpdateChatDraftMessage
                    chats[updateChat.chatId]?.let {
                        synchronized(it) {
                            it.draftMessage = updateChat.draftMessage
                            util.setChatOrder(it, updateChat.order)
                        }
                    }
                }
                UpdateNotificationSettings -> {
                    val update = resObj as TdApi.UpdateNotificationSettings
                    val scope = update.scope
                    if (scope is TdApi.NotificationSettingsScopeChat) {
                        chats[scope.chatId]?.let {
                            synchronized(it) {
                                it.notificationSettings = update.notificationSettings
                            }
                        }
                    }
                }
                UpdateUserFullInfo -> {
                    val updateUserFullInfo = resObj as TdApi.UpdateUserFullInfo
                    usersFullInfo[updateUserFullInfo.userId] = updateUserFullInfo.userFullInfo
                }
                UpdateBasicGroupFullInfo -> {
                    val updateBasicGroupFullInfo = resObj as TdApi.UpdateBasicGroupFullInfo
                    basicGroupsFullInfo[updateBasicGroupFullInfo.basicGroupId] = updateBasicGroupFullInfo.basicGroupFullInfo
                }
                UpdateSupergroupFullInfo -> {
                    val updateSupergroupFullInfo = resObj as TdApi.UpdateSupergroupFullInfo
                    supergroupsFullInfo[updateSupergroupFullInfo.supergroupId] = updateSupergroupFullInfo.supergroupFullInfo
                }
            }
        }
    }

    private inner class ExceptionHandler: Client.ExceptionHandler {
        override fun onException(t: Throwable?) {
            t?.printStackTrace(System.err)
        }
    }

    // General purpose functions

    private fun send(query: TdApi.Function, resHandler: Client.ResultHandler) {
        client?.send(query, resHandler)
    }

    private fun promptString(prompt: String): String {
        print(prompt)
        BufferedReader(InputStreamReader(System.`in`)).use {
            return it.readLine()
        }
    }

    // Application functions

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

    // Initialization functions

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
        Client.ResultHandler {
            print(it)
        }.onResult(Client.execute(TdApi.GetTextEntities("@telegram /test_command https://telegram.org telegram.me @gif @test")))
    }
}

fun main(args: Array<String>) {
    val inst = MClient()
    inst.init()
    inst.test()
}