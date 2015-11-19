package net.danielkza.http2

import scalaz.{StateT, \/, -\/, \/-}
import shapeless._
import shapeless.ops.hlist._
import akka.util.{ByteString, ByteStringBuilder}

abstract class Coder[T] {
  final type Subject = T
  type Error
  
  protected final type StateTES[TT, E, S] =  StateT[\/[E, ?], S, TT]
  
  final type EncodeStateE[E] = StateTES[Unit, E, ByteStringBuilder]
  final type EncodeState = EncodeStateE[Error]
  
  final type DecodeStateTE[TT, E] = StateTES[TT, E, ByteString]
  final type DecodeStateT[TT] = DecodeStateTE[TT, Error]
  final type DecodeState = DecodeStateT[T]

  protected implicit def stateMonad[S] = StateT.stateTMonadState[S, \/[Error, ?]]
  
  def encode(value: T, stream: ByteStringBuilder): \/[Error, Unit]
  def encode(value: T): \/[Error, ByteString] = {
    val builder = ByteString.newBuilder
    encode(value, builder).map { _ =>
      val res = builder.result()
      res
    }
  }

  def encodeS(value: T): EncodeState = StateT[\/[Error, ?], ByteStringBuilder, Unit] { in =>
    encode(value, in).map(_ => (in, ()))
  }
  
  
  def decode(bs: ByteString): \/[Error, (T, Int)]

  def decodeS: DecodeStateT[T] = StateT[\/[Error, ?], ByteString, T] { in =>
    decode(in).map { case (value, bytesRead) => in.drop(bytesRead) -> value }
  }
  
  
  final def takeS(length: Int): DecodeStateT[ByteString] = StateT[\/[Error, ?], ByteString, ByteString] { in =>
    val (left, right) = in.splitAt(length)
    \/-(right -> left)
  }
  
  final def ensureS[S](error: => Error)(cond: => Boolean): StateTES[Unit, Error, S] = {
    val SM = stateMonad[S]
    if(!cond) failS(error)
    else SM.pure(())
  }
  
  final def failS[TT, S](error: Error): StateTES[TT, Error, S] = StateT[\/[Error, ?], S, TT] { in =>
    -\/(error)
  }
}

object Coder {
  final type Aux[T, E] = Coder[T] {type Error = E} 
  
  // Trait that witnesses that an HList is a mapping of instances of Coder[_] another one, all having the same Error
  // type
  trait CodersOf[C <: HList, L <: HList] {
    type Error
  }
  
  object CodersOf {
    type Aux[C <: HC :: HList, L <: HL :: HList, E, HC <: Coder.Aux[HL, E], HL] = CodersOf[C, L] {type Error = E}
    
    //implicit def hnilCodersOf = new CodersOf[HNil, HNil] {type Error = Nothing}
    implicit def hlist1CodersOf[HC <: Coder[HL], HL] =
      new CodersOf[HC :: HNil, HL :: HNil] {type Error = HC#Error}
    implicit def hlistCodersOf[HC <: Coder[HL], C <: HList, HL, L <: HList]
      (implicit co: CodersOf[C, L] {type Error = HC#Error}) =
    {
      new CodersOf[HC :: C, HL :: L] {type Error = HC#Error}
    }
  }
  
  object dec extends Poly2 {
    final type R[T, E] = Coder[_]#DecodeStateTE[T, E]
    
    implicit def first
      [Value, Err, CurCoder]
      (implicit ev: CurCoder <:< Coder.Aux[Value, Err]) =
      at[R[HNil, Err], CurCoder]
    { (in, coder) =>
      for {
        value <- coder.decodeS
      } yield value :: HNil
    }
    
    implicit def default
      [Value, Err, CurCoder, Values <: HList]
      (implicit ev: CurCoder <:< Coder.Aux[Value, Err], cons: IsHCons[Values], p: Prepend[Values, Value :: HNil]) =
      at[R[Values, Err], CurCoder]
    { (in, coder) =>
      for {
        prev <- in
        cur <- coder.decodeS
      } yield p(prev, cur :: HNil)
    }
  }
  
  object enc extends Poly2 {
    final type R[E] = (ByteStringBuilder, \/[E, Unit])
    
    implicit def default[Value, Err, CurCoder <: Coder.Aux[Value, Err]] =
      at[R[Err], (Value, CurCoder)]
    { (in, v) =>
      val (stream, prev) = in
      val (value, coder) = v
      val res = for {
        _ <- prev
        _ <- coder.encode(value, stream)
      } yield ()
      stream -> res
    }
  }
  
  def defineCompositeCoder
    [Prod, Err, Z <: HList, Values <: HList, Coders <: HList]
    (gen: Generic.Aux[Prod, Values])(coders: Coders)(implicit 
     c: CodersOf[Coders, Values] {type Error = Err},
     zipVC: Zip.Aux[Values :: Coders :: HNil, Z],
     encodeF: LeftFolder.Aux[Z, enc.R[Err], enc.type, enc.R[Err]],
     decodeF: LeftFolder.Aux[Coders, dec.R[HNil, Err], dec.type, dec.R[Values, Err]]
    ) = 
  {
    new Coder[Prod] {      
      override type Error = Err
      
      override def encode(value: Prod, stream: ByteStringBuilder): \/[Error, Unit] = {
        val elems = gen.to(value)
        val zipped = elems.zip(coders)(zipVC)
        val init: enc.R[Error] = stream -> \/-(())
        zipped.foldLeft(init)(enc)(encodeF)._2
      }
      
      private val decoder = {
        val init = StateT.stateT[\/[Error, ?], ByteString, HNil](HNil)
        coders.foldLeft(init)(dec)(decodeF)
      }
      
      override final def decode(bs: ByteString): \/[Error, (Prod, Int)] = {
        decoder.run(bs).map { case (rem, result) => (gen.from(result), bs.length - rem.length) }
      }
      
      override final def decodeS: DecodeStateT[Prod] =
        decoder.map(gen.from)
    }
  }
}
