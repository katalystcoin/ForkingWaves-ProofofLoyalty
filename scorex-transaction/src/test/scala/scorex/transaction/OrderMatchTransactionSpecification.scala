package scorex.transaction

import org.scalatest._
import org.scalatest.prop.PropertyChecks
import scorex.transaction.exchange.{Order, OrderMatch}

class OrderMatchTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  property("OrderMatch transaction serialization roundtrip") {
    forAll(orderMatchGenerator) { om: OrderMatch =>
      val recovered = Order.parseBytes(om.bytes).get
      recovered.bytes shouldEqual om.bytes
    }
  }

  property("OrderMatch generator should generate valid orders") {
    forAll(orderMatchGenerator) { om: OrderMatch =>
      om.isValid(Seq()) shouldBe true
    }
  }

  property("OrderMatch validation") {
    forAll(orderMatchGenerator) { om: OrderMatch =>
      om.isValid(Seq()) shouldBe true
      om.isValid(Seq(om)) shouldBe false
      //TODO incorrect price/matcherFee/matcherSignature/NonMatched orders/ amount with partial match
    }
  }

  property("OrderMatch balance changes") {
    forAll(orderMatchGenerator) { om: OrderMatch =>
      om.isValid(Seq()) shouldBe true
      //TODO test
    }
  }

}
