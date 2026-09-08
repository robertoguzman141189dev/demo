# La alerta de gasto, creada el día uno.
#
# Vive aquí y no en las composiciones de EKS o k3s por dos motivos. Uno, el
# presupuesto es de la cuenta entera: repartirlo en dos módulos daría dos
# presupuestos que se ignoran mutuamente. Dos, y más importante: bootstrap se
# aplica ANTES que nada, así que la alerta existe antes de que exista un solo
# recurso capaz de gastar.
#
# ADVERTENCIA QUE CONVIENE TENER CLARA: esto avisa, no corta. AWS Budgets no
# apaga nada ni impone un techo de gasto; manda un correo y la factura sigue
# subiendo. Es un detector de humo, no un extintor. El nombre induce a error.

resource "aws_budgets_budget" "monthly" {
  name         = "event-lab-mensual"
  budget_type  = "COST"
  limit_amount = tostring(var.budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Aviso temprano: a mitad de mes con la mitad gastada, todo va según lo
  # previsto; a día 5, algo se quedó encendido.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  # La que de verdad sirve. FORECASTED avisa cuando la proyección del mes supera
  # el tope, no cuando ya lo has gastado: si dejas EKS encendido un martes, esto
  # salta el miércoles en vez del día 28. Las de ACTUAL llegan cuando el dinero
  # ya se fue.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
