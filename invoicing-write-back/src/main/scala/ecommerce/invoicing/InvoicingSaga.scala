package ecommerce.invoicing

import akka.actor.ActorPath
import ecommerce.invoicing.InvoicingSaga.InvoicingSagaConfig
import ecommerce.sales.{Money, ReservationConfirmed}
import org.joda.time.DateTime.now
import pl.newicom.dddd.actor.PassivationConfig
import pl.newicom.dddd.messaging.event.EventMessage
import pl.newicom.dddd.process.{Saga, SagaConfig}
import pl.newicom.dddd.utils.UUIDSupport.uuid

object InvoicingSaga {
  object InvoiceStatus extends Enumeration {
    type InvoiceStatus = Value
    val New, WaitingForPayment, Completed, Failed = Value
  }

  implicit object InvoicingSagaConfig extends SagaConfig[InvoicingSaga]("invoicing") {
    def correlationIdResolver = {
      case rc: ReservationConfirmed => s"$uuid" // invoiceId
      case PaymentReceived(invoiceId, _, _, _) => invoiceId
    }
  }

}

import ecommerce.invoicing.InvoicingSaga.InvoiceStatus._

class InvoicingSaga(val pc: PassivationConfig, invoicingOffice: ActorPath, override val schedulingOffice: Option[ActorPath]) extends Saga {

  override def persistenceId = s"${InvoicingSagaConfig.name}Saga-$id"

  var status = New

  def receiveEvent = {
    case em @ EventMessage(_, e: ReservationConfirmed) if status == New =>
      raise(em)
    case em @ EventMessage(_, e: PaymentReceived) if status == WaitingForPayment =>
      raise(em)
    case em @ EventMessage(_, e: PaymentFailed) if status == WaitingForPayment =>
      raise(em)
  }

  def applyEvent = {
    case ReservationConfirmed(reservationId, customerId, totalAmountOpt) =>
      val totalAmount = totalAmountOpt.getOrElse(Money())
      deliverCommand(invoicingOffice, CreateInvoice(sagaId, reservationId, customerId, totalAmount, now()))
      status = WaitingForPayment
      // schedule payment deadline
      schedule(PaymentFailed(sagaId, reservationId), now.plusMinutes(1))

    case PaymentFailed(invoiceId, orderId) =>
      // send fake payment request (to continue the process for testing purposes)
      deliverCommand(invoicingOffice, ReceivePayment(invoiceId, orderId, amount = Money(10), paymentId = uuid))

    case PaymentReceived(invoiceId, _, amount, paymentId) =>
      status = Completed
  }
}
