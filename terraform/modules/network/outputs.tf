output "vpc_id" {
  description = "Identificador de la VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "Rango de la VPC, útil para reglas de grupo de seguridad internas."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Subredes públicas, en orden estable de zona."
  value       = [for az in sort(keys(aws_subnet.public)) : aws_subnet.public[az].id]
}

output "private_subnet_ids" {
  description = "Subredes privadas. Lista vacía si no se pidieron."
  value       = [for az in sort(keys(aws_subnet.private)) : aws_subnet.private[az].id]
}
