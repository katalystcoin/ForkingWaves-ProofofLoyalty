package com.wavesplatform.lang

import cats.data._
import cats.syntax.all._
import com.wavesplatform.lang.Terms._
import com.wavesplatform.lang.ctx.{Context, PredefType}
import monix.eval.Coeval

import scala.util.{Failure, Success, Try}

object TypeChecker {

  type TypeDefs     = Map[String, TYPE]
  type FunctionSigs = Map[String, FUNCTION]
  case class TypeCheckerContext(predefTypes: Map[String, PredefType], varDefs: TypeDefs, functionDefs: FunctionSigs)

  object TypeCheckerContext {
    val empty = TypeCheckerContext(Map.empty, Map.empty, Map.empty)

    def fromContext(ctx: Context): TypeCheckerContext =
      TypeCheckerContext(predefTypes = ctx.typeDefs, varDefs = ctx.letDefs.mapValues(_.tpe), functionDefs = ctx.functions.mapValues(x => x.signature))
  }

  type TypeResolutionError      = String
  type TypeCheckResult[T]       = Either[TypeResolutionError, T]
  private type SetTypeResult[T] = EitherT[Coeval, String, T]

  private def setType(ctx: TypeCheckerContext, t: SetTypeResult[Untyped.EXPR]): SetTypeResult[Typed.EXPR] = t.flatMap {
    case x: Untyped.CONST_LONG       => EitherT.pure(Typed.CONST_LONG(x.value))
    case x: Untyped.CONST_BYTEVECTOR => EitherT.pure(Typed.CONST_BYTEVECTOR(x.value))
    case x: Untyped.CONST_STRING     => EitherT.pure(Typed.CONST_STRING(x.value))
    case Untyped.TRUE                => EitherT.pure(Typed.TRUE)
    case Untyped.FALSE               => EitherT.pure(Typed.FALSE)

    case getter: Untyped.GETTER =>
      setType(ctx, EitherT.pure(getter.ref))
        .subflatMap { ref =>
          ref.tpe match {
            case typeRef: TYPEREF =>
              val refTpe = ctx.predefTypes.get(typeRef.name).map(Right(_)).getOrElse(Left(s"Undefined type: ${typeRef.name}"))
              val fieldTpe = refTpe.flatMap { ct =>
                val fieldTpe = ct.fields.collectFirst {
                  case (fieldName, tpe) if fieldName == getter.field => tpe
                }

                fieldTpe.map(Right(_)).getOrElse(Left(s"Undefined field ${typeRef.name}.${getter.field}"))
              }

              fieldTpe.right.map(tpe => Typed.GETTER(ref = ref, field = getter.field, tpe = tpe))
            case x => Left(s"Can't access to '${getter.field}' of a primitive type $x")
          }
        }

    case expr @ Untyped.FUNCTION_CALL(name, args) =>
      val value: EitherT[Coeval, String, Typed.EXPR] = ctx.functionDefs.get(name) match {
        case Some(FUNCTION(argTypes, resultType)) =>
          if (args.lengthCompare(argTypes.size) != 0)
            EitherT.fromEither[Coeval](Left(s"Function '$name' requires ${argTypes.size} arguments, but ${args.size} are provided"))
          else {
            import cats.instances.vector._
            val actualArgTypes: Vector[SetTypeResult[Typed.EXPR]] = args.map(arg => setType(ctx, EitherT.pure(arg))).toVector
            val sequencedActualArgTypes                           = actualArgTypes.sequence[SetTypeResult, Typed.EXPR].map(x => x.zip(argTypes))

            sequencedActualArgTypes
              .subflatMap { typedExpressionArgumentsAndTypedPlaceholders =>
                val typePairs = typedExpressionArgumentsAndTypedPlaceholders.map { case ((typedExpr, tph)) => (typedExpr.tpe, tph) }
                for {
                  resolvedTypeParams <- TypeInferrer(typePairs)
                  resolvedResultType <- TypeInferrer.inferResultType(resultType, resolvedTypeParams)
                } yield Typed.FUNCTION_CALL(name, typedExpressionArgumentsAndTypedPlaceholders.map(_._1).toList, resolvedResultType)
              }

          }
        case None => EitherT.fromEither[Coeval](Left(s"Function '$name' not found"))
      }
      value

    case expr @ Untyped.BINARY_OP(a, op, b) =>
      (setType(ctx, EitherT.pure(a)), setType(ctx, EitherT.pure(b))).tupled
        .subflatMap {
          case operands @ (a, b) =>
            val aTpe = a.tpe
            val bTpe = b.tpe

            op match {
              case SUM_OP =>
                if (aTpe != LONG) Left(s"The first operand is expected to be LONG, but got $aTpe: $a in $expr")
                else if (bTpe != LONG) Left(s"The second operand is expected to be LONG, but got $bTpe: $b in $expr")
                else Right(operands -> LONG)

              case GT_OP | GE_OP =>
                if (aTpe != LONG) Left(s"The first operand is expected to be LONG, but got $aTpe: $a in $expr")
                else if (bTpe != LONG) Left(s"The second operand is expected to be LONG, but got $bTpe: $b in $expr")
                else Right(operands -> BOOLEAN)

              case AND_OP | OR_OP =>
                if (aTpe != BOOLEAN) Left(s"The first operand is expected to be BOOLEAN, but got $aTpe: $a in $expr")
                else if (bTpe != BOOLEAN) Left(s"The second operand is expected to be BOOLEAN, but got $bTpe: $b in $expr")
                else Right(operands -> BOOLEAN)

              case EQ_OP =>
                findCommonType(aTpe, bTpe) match {
                  case Some(_) => Right(operands -> BOOLEAN)
                  case None    => Left(s"Can't find common type for $aTpe and $bTpe: $a and $b in $expr")
                }
            }
        }
        .map { case (operands, tpe) => Typed.BINARY_OP(operands._1, op, operands._2, tpe) }

    case block: Untyped.BLOCK =>
      block.let match {
        case None =>
          setType(ctx, EitherT.pure(block.body)).map { resolvedT =>
            Typed.BLOCK(let = None, body = resolvedT, tpe = resolvedT.tpe)
          }

        case Some(let) =>
          (ctx.varDefs.get(let.name), ctx.functionDefs.get(let.name)) match {
            case (Some(_), _) => EitherT.leftT[Coeval, Typed.EXPR](s"Value '${let.name}' already defined in the scope")
            case (_, Some(_)) =>
              EitherT.leftT[Coeval, Typed.EXPR](s"Value '${let.name}' can't be defined because function with such name is predefined")
            case (None, None) =>
              setType(ctx, EitherT.pure(let.value)).flatMap { exprTpe =>
                val updatedCtx = ctx.copy(varDefs = ctx.varDefs + (let.name -> exprTpe.tpe))
                setType(updatedCtx, EitherT.pure(block.body))
                  .map { inExpr =>
                    Typed.BLOCK(
                      let = Some(Typed.LET(let.name, exprTpe)),
                      body = inExpr,
                      tpe = inExpr.tpe
                    )
                  }
              }
          }
      }

    case ifExpr: Untyped.IF =>
      (setType(ctx, EitherT.pure(ifExpr.cond)), setType(ctx, EitherT.pure(ifExpr.ifTrue)), setType(ctx, EitherT.pure(ifExpr.ifFalse))).tupled
        .subflatMap[String, Typed.EXPR] {
          case (resolvedCond, resolvedIfTrue, resolvedIfFalse) =>
            val ifTrueTpe  = resolvedIfTrue.tpe
            val ifFalseTpe = resolvedIfFalse.tpe
            findCommonType(ifTrueTpe, ifFalseTpe) match {
              case Some(tpe) =>
                Right(
                  Typed.IF(
                    cond = resolvedCond,
                    ifTrue = resolvedIfTrue,
                    ifFalse = resolvedIfFalse,
                    tpe = tpe
                  ))
              case None => Left(s"Can't find common type for $ifTrueTpe and $ifFalseTpe")
            }
        }

    case ref: Untyped.REF =>
      EitherT.fromEither {
        ctx.varDefs
          .get(ref.key)
          .map { tpe =>
            Typed.REF(key = ref.key, tpe = tpe)
          }
          .toRight(s"A definition of '${ref.key}' is not found")
      }

  }

  def apply(c: TypeCheckerContext, expr: Untyped.EXPR): TypeCheckResult[Typed.EXPR] = {
    def result = setType(c, EitherT.pure(expr)).value().left.map { e =>
      s"Typecheck failed: $e"
    }
    Try(result) match {
      case Failure(ex)  => Left(ex.toString)
      case Success(res) => res
    }
    result

  }
}
