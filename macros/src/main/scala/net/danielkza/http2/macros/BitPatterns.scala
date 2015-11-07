package net.danielkza.http2.macros

import scala.reflect.macros.whitebox
import java.nio.ByteOrder

object BitPatterns {
  private def extractLiteral(c: whitebox.Context): String = {
    import c.universe._

    try {
      val (args, prefix) = c.prefix.tree match {
        case q"""$wrapper($stringContext.apply(..$args)).${prefix: TermName}"""
          => (args, Some(prefix))
        case q"""$wrapper($stringContext.apply(..$args))"""
          => (args, None)
      }
  
      (args, prefix) match {
        case (List(Literal(Constant(str: String))), _) => str
      }
    } catch { case e: MatchError =>
      c.abort(c.enclosingPosition, "Invalid binary literal, must be a string literal without interpolation")
    }
  }
  
  private def parseBinaryLiteral(c: whitebox.Context)(value: String, allowPlaceholder: Boolean = false)
    : IndexedSeq[(Byte, Byte)] =
  {
    val clean = value.replaceAll("\\s", "")
    clean.foreach { 
      case '0' | '1' =>
      case '-' if allowPlaceholder => 
      case _ =>
        c.abort(c.enclosingPosition, "Invalid binary literal, must only contain 1, 0, whitespaces, dashes (only if pattern)")
    }
    
    clean.grouped(8).map { s =>
      val maskStr = clean.map { case '-' => '0' case _ => '1' }
      val mask = Integer.parseUnsignedInt(maskStr, 2)
      val valueStr = clean.replace('-', '0')
      val value = Integer.parseUnsignedInt(valueStr, 2)
      (value.toByte, mask.toByte)
    }.toIndexedSeq
  }

  def binaryLiteralImpl[T : c.WeakTypeTag](c: whitebox.Context)(args: c.Tree*): c.Expr[T] = {
    import c.universe._
    
    val bytes = parseBinaryLiteral(c)(extractLiteral(c))
    var l: Long = 0
    bytes.indices.foreach { idx =>
      val (byte, mask) = bytes(idx)
      l = (l << 8) + (byte & mask)
    }
    
    val (tByte, tShort, tInt, tLong) = (c.typeOf[Byte], c.typeOf[Short], c.typeOf[Int], c.typeOf[Long])
    
    implicitly[c.WeakTypeTag[T]].tpe match {
      case `tByte` =>
        val b = l.toByte
        c.Expr[Byte](q"""$b: Byte""").asInstanceOf[c.Expr[T]]
      case `tShort` =>
        val s = l.toShort
        c.Expr[Short](q"""$s: Short""").asInstanceOf[c.Expr[T]]
      case `tInt` =>
        val i = l.toInt
        c.Expr[Int](q"""$i: Int""").asInstanceOf[c.Expr[T]]
      case `tLong` =>
        c.Expr[Long](q"""$l: Long""").asInstanceOf[c.Expr[T]]
      case tpe =>
        c.abort(c.enclosingPosition, s"Unsupported type ${tpe.toString}")
    }
  }

  def binaryLiteralExtractorImpl[T](c: whitebox.Context)(x: c.Tree)(implicit tt: c.WeakTypeTag[T]) = {
    import c.universe._
    import definitions._
    
    val bytes = parseBinaryLiteral(c)(extractLiteral(c), allowPlaceholder = true)
    
    def expr(trees: c.Tree*) = {
      val tree = trees.reduceLeft((a, b) => q"""$a && $b""")
      
      q"""
        new {
          @inline final def unapply(b: ${tt.tpe}): Boolean = $tree
        }.unapply($x)
      """
    } 
    def byteComparison(byte: Byte, mask: Byte, shift: Int = 0) = {
      q"""((b >>> $shift) & $mask) == $byte"""
    }
    
    def bytesComparisons = {
      val exprs = bytes.zipWithIndex.map { case ((byte, mask), index) => byteComparison(byte, mask, index * 8) }
      expr(exprs: _*)
    }
      
    def literalError = c.abort(x.pos, "Binary literal to big for primitive type")
    
    val (byteClass, shortClass, intClass, longClass) = (ByteClass, ShortClass, IntClass, LongClass)
    tt.tpe.typeSymbol.asClass match {
      case `byteClass`  if bytes.length == 1 => bytesComparisons
      case `byteClass`                       => literalError
      case `shortClass` if bytes.length <= 2 => bytesComparisons
      case `shortClass`                      => literalError
      case `intClass`   if bytes.length <= 4 => bytesComparisons
      case `intClass`                        => literalError
      case `longClass`  if bytes.length <= 8 => bytesComparisons
      case `longClass`                       => literalError
      case _ =>
        c.abort(c.enclosingPosition, "Unexpected non-primitive type in pattern")
    }
  }
}
