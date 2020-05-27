package com.undsf.mirai

import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User

class Instruction {
    var command = ""
    var args = ArrayList<String>()
    lateinit var sender: User
    var group: Group? = null
}