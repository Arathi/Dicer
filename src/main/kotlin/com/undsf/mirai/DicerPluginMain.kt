package com.undsf.mirai

import net.mamoe.mirai.console.command.Command
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.plugins.ConfigSectionImpl
import net.mamoe.mirai.console.plugins.PluginBase
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.event.subscribeFriendMessages
import net.mamoe.mirai.event.subscribeGroupMessages
import net.mamoe.mirai.message.MessageEvent
import net.mamoe.mirai.utils.info
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.random.Random

object DicerPluginMain : PluginBase() {
    private val config = loadConfig("dicer.yml")
    private val rollDicePattern = Pattern.compile("r(\\d{0,3})(d(\\d{0,3}))?")

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
            handleBot(event, inst.group, inst.args)
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

    suspend fun handleBot(event: MessageEvent, group: Group?, args: List<String>) {
        if (group == null) {
            event.reply("请在群里执行该命令")
            return
        }

        val groupId = group.id
        val hint = "参数格式错误"
        if (args.size != 1) {
            event.reply(hint)
            return
        }

        when (args[0]) {
            "on" -> {
                saveGroupSwitch(groupId, group.name, "on")
                event.reply("骰娘已启动")
            }
            "off" -> {
                saveGroupSwitch(groupId, group.name, "off")
                event.reply("骰娘已关闭")
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

    suspend fun handleHelp(event: MessageEvent) {
        event.reply("""Usage:
.bot on    开启bot
.bot off   关闭bot
.help      显示帮助信息
.r         相当于.r1d100
.r3        相当于.r3d100
.rd        相当于.r1d100
.r3d       相当于.r3d100
.rd20      相当于.r1d20
.r{x}d{y}  投x颗y面骰子""".trimIndent())
    }

    suspend fun handleRoll(event: MessageEvent, matcher: Matcher) {
        var amount = 1
        var face = 100
        if (matcher.groupCount() >= 1) {
            val amountInput = matcher.group(1)
            if (amountInput != null && amountInput.isNotEmpty()) amount = amountInput.toInt()
        }
        if (matcher.groupCount() >= 3) {
            val faceInput = matcher.group(3)
            if (faceInput != null && faceInput.isNotEmpty()) face = faceInput.toInt()
        }
        val results = roll(amount, face)
        val sb = StringBuilder()
        sb.append("${event.sender.nick}掷骰：")
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
