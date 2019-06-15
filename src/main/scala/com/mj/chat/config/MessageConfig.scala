package com.mj.chat.config

import com.typesafe.config.ConfigFactory


/**
  * Created by rmanas001c on 1/6/2018
  */
trait MessageConfig {
  val conf = ConfigFactory.load("messages.conf")

  /* Success Mesages*/
  val updateSuccess = conf.getString("successMessages.updateSuccess")
  val deleteSuccess = conf.getString("successMessages.deleteSuccess")


  /* Error Codes & descriptions*/

  val updateFailed = conf.getString("errorMessages.updateFailed")
  val noRecordFound = conf.getString("errorMessages.noRecordFound")




}

