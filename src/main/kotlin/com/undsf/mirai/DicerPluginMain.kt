package com.undsf.mirai

import net.mamoe.mirai.console.command.Command
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.plugins.PluginBase
import net.mamoe.mirai.console.utils.isManager
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.event.subscribeFriendMessages
import net.mamoe.mirai.event.subscribeGroupMessages
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.utils.info
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.random.Random

object DicerPluginMain : PluginBase() {
    private val config = loadConfig("dicer.yml")
    private val rollDicePattern = Pattern.compile("^r(\\d{0,2})(d(\\d{0,3}))?\$")

    private val prefix by lazy {
        config.setIfAbsent("command-prefix", ".")
        config.getString("command-prefix")
    }

    override fun onLoad() {
        super.onLoad()

        val dbPath = "${dataFolder.absolutePath}/dicer.db"
        Database.connect(
            url = "jdbc:sqlite:${dbPath}",
            driver = "org.sqlite.JDBC"
        )

        transaction {
            SchemaUtils.create(GroupSwitch)
        }

        config.save()
    }

    override fun onEnable() {
        super.onEnable()
        logger.info("Dicer Plugin loaded!")

        subscribeFriendMessages {
            startsWith(prefix) {
                val inst = buildInstriction(it, this.sender, null)
                handleInstriction(this, inst)
            }
        }

        subscribeGroupMessages {
            startsWith(prefix) {
                val inst = buildInstriction(it, this.sender, this.group)
                handleInstriction(this, inst)
            }
        }
    }

    fun buildInstriction(input: String, sender: User, group: Group?): Instruction {
        val argsRaw = input.split(" ", "\t")
        val inst = Instruction()
        for (arg in argsRaw) {
            if (arg.isNotEmpty()) {
                if (inst.command == "") {
                    inst.command = arg
                }
                else {
                    inst.args.add(arg)
                }
            }
        }
        inst.sender = sender
        inst.group = group
        return inst
    }

    suspend fun handleInstriction(event: MessageEvent, inst: Instruction) {
        if (inst.command == "bot") {
            handleBot(event, inst)
            return
        }

        if (inst.group != null) {
            val groupId = inst.group!!.id
            var enabled = false

            transaction {
                val result = GroupSwitch.select {
                    GroupSwitch.id eq groupId
                }.firstOrNull()

                if (result != null) {
                    val stat = result[GroupSwitch.stat]
                    enabled = stat == "on"
                }
            }

            if (!enabled) {
                logger.info { "已关闭群${groupId}的机器人" }
                return
            }
        }

        when (inst.command) {
            "help" -> {
                handleHelp(event)
                return
            }
        }

        val matcher = rollDicePattern.matcher(inst.command)
        if (matcher.find()) {
            handleRoll(event, matcher)
        }
    }

    suspend fun handleBot(event: MessageEvent, inst: Instruction) {
        if (inst.group == null) {
            event.reply("请在群里执行该命令")
            return
        }

        val group = inst.group
        val args = inst.args
        val member: Member = inst.sender as Member

        if (!member.isManager && !member.isAdministrator() && !member.isOperator()) {
            event.quoteReply("机器人管理员或群管理员才有权限执行该命令")
            return
        }

        val groupId = group!!.id
        val hint = "参数格式错误"
        if (args.size != 1) {
            event.reply(hint)
            return
        }

        when (args[0]) {
            "on" -> {
                saveGroupSwitch(groupId, group.name, "on")
                event.reply("启动骰娘")
            }
            "off" -> {
                saveGroupSwitch(groupId, group.name, "off")
                event.reply("关闭骰娘")
            }
            "status" -> {
                when (getGroupSwitch(groupId)) {
                    "on" -> {
                        event.reply("骰娘当前已开启")
                    }
                    "off" -> {
                        event.reply("骰娘当前已关闭")
                    }
                }
            }
            else -> {
                event.reply("开关参数无效")
            }
        }
    }

    fun saveGroupSwitch(groupId: Long, groupName: String, status: String) {
        transaction {
            val query: Query = GroupSwitch.select {
                GroupSwitch.id eq groupId
            }

            if (query.count() == 0L) {
                GroupSwitch.insert {
                    it[id] = groupId
                    it[name] = groupName
                    it[stat] = status
                }
            }
            else {
                GroupSwitch.update( { GroupSwitch.id eq groupId } ) {
                    it[stat] = status
                }
            }
        }
    }

    fun getGroupSwitch(groupId: Long): String {
        var status = "off"
        transaction {
            val query: Query = GroupSwitch.select {
                GroupSwitch.id eq groupId
            }
            if (query.empty()) {
                val gs = query.first()
                status = gs[GroupSwitch.stat]
            }
        }
        return status
    }

    suspend fun handleHelp(event: MessageEvent) {
        val sb = StringBuilder()
        sb.appendln("Usage:")
        sb.appendln(".bot on\t开启bot")
        sb.appendln(".bot off\t关闭bot")
        sb.appendln(".bot status\t查看bot状态")
        sb.appendln(".help\t\t显示帮助信息")
        sb.appendln(".r\t\t相当于.r1d100")
        // sb.appendln(".r3\t\t相当于.r3d100")
        sb.appendln(".rd\t\t相当于.r1d100")
        // sb.appendln(".r3d\t\t相当于.r3d100")
        // sb.appendln(".rd20\t\t相当于.r1d20")
        sb.append(".r{x}d{y}\t投x枚y面骰子，其中x<=30且y<=100")
        event.reply(sb.toString())
    }

    suspend fun handleRoll(event: MessageEvent, matcher: Matcher) {
        var amount = 1
        var face = 100

        if (matcher.groupCount() >= 1) {
            val amountInput = matcher.group(1)
            if (amountInput != null && amountInput.isNotEmpty()) amount = amountInput.toInt()
        }
        if (amount < 1) {
            amount = 1
        }
        if (amount > 30) {
            amount = 30
        }

        if (matcher.groupCount() >= 3) {
            val faceInput = matcher.group(3)
            if (faceInput != null && faceInput.isNotEmpty()) face = faceInput.toInt()
        }
        if (face < 2) {
            face = 2
        }
        if (face > 100) {
            face = 100
        }

        val results = roll(amount, face)
        val sb = StringBuilder()

        sb.append("${event.sender.nick}掷骰")
        if (amount > 1) {
            sb.append("${amount}次")
        }
        sb.append("：")

        var sum = 0
        for (r in results) {
            sum += r
            sb.appendln()
            sb.append("D${face}=${r}")
        }

        if (amount > 1) {
            sb.appendln()
            sb.append("共计${sum}点")
        }

        event.reply(sb.toString())
    }

    private fun roll(amount: Int, face: Int): List<Int> {
        val results = ArrayList<Int>()
        for (i in 1 .. amount) {
            val point = Random.nextInt(1, face + 1)
            results.add(point)
        }
        return results
    }

    override fun onCommand(command: Command, sender: CommandSender, args: List<String>) {
        super.onCommand(command, sender, args)
    }
}

object GroupSwitch: Table() {
    var id = long("id")
    var name = varchar("name", 256)
    var stat = varchar("stat", 5)
}
