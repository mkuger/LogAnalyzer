package input

import java.io._
import java.text.{DateFormat, SimpleDateFormat}
import java.time.Instant

import akka.actor.ActorRef
import api.InputException
import kuger.loganalyzer.core.api.{Input, InputContainer, LogStatement}
import messages.{InputDrainedEvent, LogStatementEvent}

class StatementParser(inputContainer: InputContainer, downstream: Array[ActorRef]) extends Thread with Input {
  val DATE_FORMAT: DateFormat = new SimpleDateFormat(inputContainer.getTimestampPattern)

  setDaemon(true)

  private val reader: BufferedReader = new BufferedReader(inputContainer.getReader)
  private var nextLine: String = null
  private var currentStatement: LogStatement = null

  private def getNextStatement: LogStatement = {
    var line = nextLine
    if (line == null) {
      return null
    }
    var timestamp: Instant = null
    while ( {
      timestamp = parseLine(line)
      timestamp
    } == null) {
      line = reader.readLine
      if (line == null) {
        return null
      }
    }
    val stringBuilder = new StringBuilder
    stringBuilder.append(line)
    line = reader.readLine
    var lineIsNull = false
    var firstLine = true
    while (line != null && parseLine(line) == null && !lineIsNull) {
      if (!firstLine) {
        firstLine = false
        stringBuilder append System.lineSeparator()
      }
      stringBuilder append line
      line = reader.readLine
      if (line == null) {
        lineIsNull = true
      }
    }
    nextLine = line
    new LogStatement(timestamp, stringBuilder.toString())
  }

  private def parseLine(line: String): Instant = {
    try {
      val date = DATE_FORMAT parse line
      date.toInstant
    }
    catch {
      case e: Throwable =>
        null
    }
  }

  private def pop: LogStatement = {
    try {
      val temp = currentStatement
      currentStatement = getNextStatement
      println("Pop!:" +temp)
      temp
    }
    catch {
      case e: IOException =>
        throw new InputException("Could not create LogStatement.", e)
    }
  }

  override def run {
    nextLine = reader.readLine
    currentStatement = getNextStatement
    var statement: LogStatement = null
    try {
      var counter = 0
      while ( {
        statement = pop
        statement
      } != null) {
        val message = LogStatementEvent(statement)
        downstream foreach (_ ! message)
        counter += 1
      }
      println("FileInput " + inputContainer + " finished! Duration[ms]: " + counter)
      downstream foreach (_ ! InputDrainedEvent(this))
    }
    catch {
      case e: RuntimeException =>
        e.printStackTrace()
    }
  }

  override def getIdentifier = inputContainer.getIdentifier
}

