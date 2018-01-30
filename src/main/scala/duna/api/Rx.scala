package architect
package duna
package api

import java.util.UUID 
import duna.kernel.{ Computation, Task, Callback, Timer, ProcessingTime, ComputedList }
import duna.eventSourcing.{Event, EventManager}
import duna.api.StateManager.{ Exec }
import scala.util.{Try, Success, Failure}
import java.util.concurrent.CompletableFuture

class Rx[A](calculation: Rx[A] => A, private val bufferSize: Int, manager: StateManager) extends Reactive[A](manager, bufferSize){ self =>
  
  private val eventManager: EventManager[Time, Int] = EventManager(bufferSize)
  private val dependencyManager: DependencyManager[Int, A] = DependencyManager() // all dependent Vars
  private val dataManager: DataManager[Time, A] = DataManager(Time(), calculation(self))
  private val computed: ComputedList[Rx, A] = ComputedList()
  
  def newValue(hashVar: Int, value: A): ProcessingTime[Task[Seq[Failure[Any]]]]  = synchronized{
    
    dependencyManager.put(hashVar, value) 

    process(recalc)

  }
  
  def addEvent(time: Time, hashVar: Int) = {

    val event = Event(time,  hashVar)

    eventManager.emit(event)
    
  }

  def dependency(hashVar: Int, init: A): A = {

    dependencyManager.read(hashVar) match{
      case Success(value) => {

        value
      } 
      case Failure(error) => {
          
        dependencyManager.put(hashVar, init)
        init

      }
    }
  }

  
  def calculateRx(rxValue:  A)  = {
      
    val computedRes = computed.signal{rx => 
              Try{rx.newValue(self.hashCode, rxValue)}
          }

    val subscriptionRes =  subscriptionManager.run(rxValue).filter{_.isFailure}

    val written = Seq(dataManager.write(Time(), rxValue)).filter{_.isFailure}

    val newRes = computedRes ++ written 

    newRes ++ subscriptionRes 
          
  }

  def recalc  = {
   // If there is an event
   eventManager.process(() => eventManager.read){(time: Time, hashVar: Int) => { 
        // And if there are values 
      if(dependencyManager.hasNext(hashVar)){
        
        // We move the queue to get the next value
            val dependencyRes = Seq(dependencyManager.get(hashVar)).filter{_.isFailure}.asInstanceOf[Seq[Failure[Any]]] 
            
            val calculated: Try[A] = Try{ calculation(self) }
            
            val results = calculated match{

              case  Success(value) => {

                  eventManager.consume
                  calculateRx(value)

                }
              case Failure(error) => Seq(Failure(error))
            }
            results.asInstanceOf[Seq[Failure[Any]]]   ++ dependencyRes
            
        }else{
          Seq(Failure(new Throwable("Nothing was defined")))
        }
          
      }
    }
  }


  def apply()(implicit rx: Rx[A]): A = {
    
    computed.add(rx) // memorize dependency from rx
    // find a current value
    rx.dependency(self.hashCode, dataManager.read)

  }

  def now = {
      task.waiting
      dataManager.read 
  }

}
object Rx{

  def apply[A](calculation: Rx[A] => A, bufferSize: Int = 5)(implicit manager: StateManager): Rx[A] = new Rx(calculation, bufferSize, manager)

}