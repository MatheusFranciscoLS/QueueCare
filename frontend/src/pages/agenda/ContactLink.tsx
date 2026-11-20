import { formatPhone, whatsappLink } from '../../api/phone'

/**
 * WhatsApp do tutor na agenda e na fila: o número formatado é o próprio link para abrir a conversa (sem integração paga). Abre em outra aba;
 * `message` é opcional (por exemplo, o aviso de "pronto").
 */
export function ContactLink({ phone, name, message }: { phone: string; name: string; message?: string }) {
  return (
    <a className="appt-contact" href={whatsappLink(phone, message)} target="_blank" rel="noopener noreferrer">
      {formatPhone(phone)}
      <span className="pp-sr"> (abrir conversa no WhatsApp com {name})</span>
    </a>
  )
}
