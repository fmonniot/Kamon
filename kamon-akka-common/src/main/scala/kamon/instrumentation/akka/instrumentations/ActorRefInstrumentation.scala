package kamon.instrumentation.akka.instrumentations

import akka.actor.{ActorPath, ActorRef, Props}
import kamon.Kamon
import kamon.context.Storage.Scope
import kamon.instrumentation.context.HasContext
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.libs.net.bytebuddy.asm.Advice

class ActorRefInstrumentation extends InstrumentationBuilder {

  /**
    * This instrumentation helps with keeping a track of types in the entire actor path of any given actor, which allows
    * to have proper information when evaluating auto-grouping.
    */
  onTypes("akka.actor.LocalActorRef", "akka.actor.RepointableActorRef")
    .mixin(classOf[HasGroupPath.Mixin])
    .advise(isConstructor, ActorRefConstructorAdvice)

  /**
    * This ensures that if there was any Context available when an Actor was created, it will also be available when its
    * messages are being transferred from the Unstarted cell to the actual cell.
    */
  onType("akka.actor.RepointableActorRef")
    .mixin(classOf[HasContext.MixinWithInitializer])
    .advise(method("point"), RepointableActorRefPointAdvice)
}

trait HasGroupPath {
  def groupPath: String
  def setGroupPath(groupPath: String)
}

object HasGroupPath {

  class Mixin(@volatile var groupPath: String) extends HasGroupPath {
    override def setGroupPath(groupPath: String): Unit =
      this.groupPath = groupPath
  }
}

object ActorRefConstructorAdvice {

  @Advice.OnMethodExit
  def exit(@Advice.This ref: HasGroupPath, @Advice.Argument(1) props: Props, @Advice.Argument(4) parent: ActorRef,
      @Advice.Argument(5) path: ActorPath): Unit = {

    val name = path.name
    val elementCount = path.elements.size

    val parentPath = if(parent.isInstanceOf[HasGroupPath]) parent.asInstanceOf[HasGroupPath].groupPath else ""
    val refGroupName = {
      if(elementCount == 1)
        if(name == "/") "" else name
      else
        ActorCellInfo.simpleClassName(props.actorClass())
    }

    val refGroupPath = if(parentPath.isEmpty) refGroupName else parentPath + "/" + refGroupName
    //println(s"Setting group to [$refGroupPath] on ${ref} - ${ref.hashCode()}")
    ref.setGroupPath(refGroupPath)

  }
}

object RepointableActorRefPointAdvice {

  @Advice.OnMethodEnter
  def enter(@Advice.This repointableActorRef: Object): Scope =
    Kamon.store(repointableActorRef.asInstanceOf[HasContext].context)

  @Advice.OnMethodExit
  def exit(@Advice.Enter scope: Scope): Unit =
    scope.close()
}


