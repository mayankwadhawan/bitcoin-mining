import akka.actor._
import akka.actor.{Props, ActorSystem}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import akka.actor.ReceiveTimeout
import akka.actor.RootActorPath
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.actor.ActorLogging
import akka.actor.Terminated
import akka.actor._
import scala.util.Random
import akka.actor.Props
import akka.routing.RoundRobinPool
import java.security.MessageDigest
/**
 * Created by PRATEEK on 9/12/2015.
 */

case class peformWork(start: Int, quantity: Int, leadingZeros: Int, gatorId: String, actorId: Int, inputString: String)
case class GetCoins(coinList: ListBuffer[Tuple2[String, String]] )
case object noWork
case object MineCoins
case object Miningstart
case object clientConnected
case class Result(coinList: ListBuffer[Tuple2[String, String]] )
case class Work(start: Int, quantity: Int, leadingZeros: Int, gatorId: String, actorId: Int, inputString: String, isRemoteClient: Boolean)
case class RemoteResult(coinList: ListBuffer[Tuple2[String, String]] )

class Worker extends Actor with ActorLogging{
  def findCoins(start:Int, quantity:Int, leadingZeros: Int, gatorID: String, actorId: Int, inputString: String):ListBuffer[Tuple2[String, String]]  ={
    return digestSHA256(inputString, leadingZeros, gatorID, start, quantity, actorId)
  }
  def digestSHA256(input: String, zeros: Int,  gatorId: String, start: Int, quantity: Int, actorId: Int) : ListBuffer[Tuple2[String, String]] = {
    var hasZeroes: String = ""
    var bitCoins = new ListBuffer[Tuple2[String, String]]()
    for (i <- 1 to zeros)
      hasZeroes += "0"
    for(attempts <- start to quantity){
      val s : String = gatorId +";" +scala.util.Random.alphanumeric.take(15).mkString  + attempts.toString()+ actorId.toString()
      val digest : String = MessageDigest.getInstance("SHA-256").digest(s.getBytes)
        .foldLeft("")((s: String, b: Byte) => s + Character.forDigit((b & 0xf0) >> 4, 16) +
        Character.forDigit(b & 0x0f, 16))
      if(digest.startsWith(hasZeroes)){
        bitCoins += ((s, digest))
      }
    }
    return bitCoins
  }
  def receive={
    case Work(start, quantity, leadingZeros, gatorId, actorId, inputString, isRemoteClient)=>
      if(!isRemoteClient)
        sender ! Result(findCoins(start, quantity, leadingZeros, gatorId, actorId, inputString))
      else
        sender ! GetCoins(findCoins(start, quantity, leadingZeros, gatorId, actorId, inputString))
  }
}

class RemoteClients(ipAddress: String) extends Actor with ActorLogging{
  val main = new Address("akka.tcp","Bitcoin-server", ipAddress, 2552)
  val noOfWorkers: Int = 8
  val remoteWorker = context.actorOf(RoundRobinPool(noOfWorkers).props(Props(new Worker())), "scheduleRemoteWorker")
  val remote = context.actorSelection(RootActorPath(main) / "user" / "Server")
  for(i <- 1 to noOfWorkers){
      remote ! clientConnected
  }
  def receive = {
    case "Start" =>
       // println("I am Up")
    case peformWork(start, quantity, leadingZeros, gatorId, actorId, inputString) =>
        //println ("Client is ready to do the work")
	remoteWorker ! Work(start, quantity, leadingZeros, gatorId, actorId, inputString, true)  
    case GetCoins(list: ListBuffer[Tuple2[String, String]])=>
        remote ! RemoteResult(list)
    case noWork =>
        //println(" The No Work Signal Received")
        context.stop(self)
        context.system.shutdown()
  }
}

class BitcoinServer(requiredZeroes: Int) extends Actor with ActorLogging{

  val startTime = System.currentTimeMillis()
  val gatorId : String = "prateek.j1"
  val inputString : String = ""
  var actorId : Int = 1
  var currentTasks: Int =_
  val noOfWorkers: Int = 8
  val totalTask : Int = 10000000
  var workDone: Int = 0
  var activeProcess: Int = 0;
  var startTask : Int = 0
  var taskLeft : Int =  totalTask
  val sizeOfWork: Int = 100000
  var leadingZeros = requiredZeroes
  var duration: Double =_
  val workerScheduler = context.actorOf(RoundRobinPool(noOfWorkers).props(Props(new Worker())), "masterActor")
  var coins = new ListBuffer[Tuple2[String, String]]()
  def displaySolution(coins: ListBuffer[Tuple2[String, String]]) = {
    for(coin: Tuple2[String, String]<-coins){
      println(coin._1+"     "+coin._2)
    }
  }
  override def postStop() {
    //println("The Post Stop")
    context.system.shutdown()
  }
  def receive={
 
   case Miningstart =>
         self ! MineCoins

    case MineCoins=>
      //println("Okay I m here")
      var task: Int = startTask
      for(i <- 1 to noOfWorkers){
         workerScheduler ! Work(startTask, startTask + sizeOfWork , leadingZeros, gatorId, 0, inputString, false)
         startTask += sizeOfWork
         taskLeft -= sizeOfWork
          activeProcess += 1
      }
      //println("Process Are " + activeProcess)

    case Result(list)=>
      for(coin:Tuple2[String, String]<-list){
        coins+=coin
      }
      activeProcess -= 1
      //println("Process Left " + activeProcess)
      //println (" Got Result")
      if(taskLeft > 0){
	sender ! Work(startTask, startTask + sizeOfWork , leadingZeros, gatorId, 0, inputString, false)
        startTask += sizeOfWork
        taskLeft -= sizeOfWork
        activeProcess += 1
      }
      else if(activeProcess == 0) {
          displaySolution(coins)
          duration=(System.currentTimeMillis()-startTime)/1000d
          println(" The No of Coins found" + coins.size)
          println("Time taken=%s seconds".format(duration))
          context.stop(workerScheduler)
          context.system.shutdown()
      }
    case RemoteResult(list) =>
      for(coin:Tuple2[String, String]<-list){
        coins+=coin
      }
      //println ("Got Result From Remote")
      activeProcess -= 1
      if(taskLeft > 0){
      	sender ! peformWork(startTask, startTask + sizeOfWork, leadingZeros, gatorId, actorId, inputString)
      	startTask += sizeOfWork
      	taskLeft -= sizeOfWork
      	actorId += 1 
        activeProcess += 1
      } 
      else if(activeProcess == 0) {
          displaySolution(coins)
          duration=(System.currentTimeMillis()-startTime)/1000d
          println(" The No of Coins found" + coins.size)
          println("Time taken=%s seconds".format(duration))
          sender ! noWork
          context.stop(self)
          context.system.shutdown()
      }
        
    case clientConnected =>
        if( taskLeft <= 0)
          sender ! noWork
       else{
	sender ! peformWork(startTask, startTask + sizeOfWork, leadingZeros, gatorId, actorId, inputString)
        startTask += sizeOfWork
        taskLeft -= sizeOfWork
        actorId += 1
        activeProcess += 1
       }
  }
}

object Main extends App{
  override def main (args: Array[String]) {
      if(args(0).length() > 7){
       //println("Okay I m the Remote IP")
        val system=ActorSystem("Bitcoin-server")
        val clientActor = system.actorOf(Props(new RemoteClients(args(0))),"clientActor")
      }
      else{
       // println("Hello I am in Here")
        val system=ActorSystem("Bitcoin-server")
       // println("Hello I am in Here")
        val masterActor = system.actorOf(Props(new BitcoinServer(args(0).toInt)),"Server")
        masterActor ! Miningstart
        system.awaitTermination()
      }
  }
}

