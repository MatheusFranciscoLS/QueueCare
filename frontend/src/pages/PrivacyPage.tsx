import { Link } from 'react-router-dom'
import { PRIVACY_POLICY_VERSION } from '../api/privacy'
import { useAuth } from '../auth/session'

/**
 * Política de Privacidade (ADR 0027): aberta a todos, com ou sem login. Linguagem simples e específica do que o app faz; o que NÃO faz (rastreio, anúncios,
 * venda de dados) também está escrito, porque é o que a pessoa quer saber. A versão aqui é a mesma que o cadastro grava.
 */
export function PrivacyPage() {
  const { user } = useAuth()
  const isCustomer = user?.role === 'CUSTOMER'
  return (
    <article className="card privacy">
      <h1>Política de Privacidade</h1>
      <p className="hint">Versão {PRIVACY_POLICY_VERSION}</p>
      <p className="lead">
        O Pelo &amp; Ponto agenda banho e tosa. Esta página explica, sem rodeio, quais dados do tutor e do pet o app guarda, para quê, com quem eles passam e como você os controla.
      </p>

      <section aria-labelledby="priv-projeto">
        <h2 id="priv-projeto">Sobre este projeto</h2>
        <p>
          Este app é um <strong>projeto de portfólio</strong>: foi construído para demonstrar engenharia de software, não é um serviço comercial. Mesmo assim, ele trata os dados
          como se fosse. Por isso, <strong>use dados fictícios</strong> ao testar.
        </p>
      </section>

      <section aria-labelledby="priv-dados">
        <h2 id="priv-dados">O que o app guarda</h2>
        <ul>
          <li>
            <strong>Conta:</strong> nome, e-mail, telefone (opcional, para o estúdio chamar você pelo WhatsApp) e a senha, que fica guardada só em forma de hash, nunca em texto.
          </li>
          <li>
            <strong>Pets:</strong> nome, espécie, raça, porte, pelo, peso, temperamento, vacinas e observações de saúde que você escrever.
          </li>
          <li>
            <strong>Agendamentos:</strong> estúdio, serviço, dia e horário, valor, e a cópia dos dados do pet no momento da reserva. Também a lista de espera, se você entrar nela.
          </li>
          <li>
            <strong>Registros de funcionamento:</strong> quando uma ação é feita (chegou, concluiu, cancelou) e por quem, para o histórico do agendamento.
          </li>
        </ul>
        <p>
          <strong>O que o app não faz:</strong> não usa cookies de rastreamento, não tem anúncios, não mede o seu uso com ferramentas de terceiros e não vende nem cede dados.
        </p>
      </section>

      <section aria-labelledby="priv-finalidade">
        <h2 id="priv-finalidade">Para que serve cada dado</h2>
        <ul>
          <li>Conta e telefone: identificar você, entrar no app e permitir que o estúdio avise do seu horário.</li>
          <li>Dados do pet: o estúdio saber como atender com segurança (alergias, comportamento) e estimar a duração.</li>
          <li>Agendamentos e histórico: marcar, remarcar, cancelar, atender e resolver dúvidas sobre o que aconteceu.</li>
        </ul>
      </section>

      <section aria-labelledby="priv-quem">
        <h2 id="priv-quem">Quem recebe os dados</h2>
        <ul>
          <li>
            <strong>O estúdio em que você agenda</strong> vê seu nome, telefone, os dados dos pets que levou e o histórico de agendamentos <em>com ele</em>. Nunca vê os agendamentos
            em outros estúdios.
          </li>
          <li>
            <strong>Infraestrutura:</strong> o site, a API e o banco de dados rodam em provedores de hospedagem (Vercel, Render e Neon), que guardam os dados em nome do app.
          </li>
          <li>
            <strong>WhatsApp:</strong> só quando alguém da equipe toca em &quot;Lembrar&quot; ou &quot;Avisar&quot;, o WhatsApp abre com a mensagem pronta. O app não envia nada
            sozinho.
          </li>
        </ul>
      </section>

      <section aria-labelledby="priv-prazo">
        <h2 id="priv-prazo">Por quanto tempo</h2>
        <p>
          Enquanto sua conta existir. Ao <strong>excluir a conta</strong>, seu nome, e-mail, telefone, senha e os dados dos seus pets são removidos na hora (a conta é anonimizada), e os
          horários que ainda estavam marcados são cancelados. Ficam apenas o dia, o serviço e o valor dos atendimentos já feitos, sem identificar você, porque são o registro do estúdio.
        </p>
      </section>

      <section aria-labelledby="priv-direitos">
        <h2 id="priv-direitos">Seus direitos e como exercê-los</h2>
        <ul>
          <li>
            <strong>Ver e levar seus dados:</strong> em{' '}
            {isCustomer ? <Link to="/perfil">Meu perfil</Link> : <span>Meu perfil</span>}, toque em &quot;Baixar meus dados&quot;. Você recebe um arquivo com tudo o que o app guarda de você.
          </li>
          <li>
            <strong>Corrigir:</strong> altere seu telefone em Meu perfil e os dados dos pets em Meus pets.
          </li>
          <li>
            <strong>Excluir:</strong> em Meu perfil, &quot;Excluir minha conta&quot;. Pede a sua senha e não tem volta.
          </li>
          <li>
            <strong>Perguntar ou reclamar:</strong> fale com o responsável pelo projeto (veja o perfil do repositório no GitHub). Você também pode recorrer à Autoridade Nacional de
            Proteção de Dados (ANPD).
          </li>
        </ul>
      </section>

      <section aria-labelledby="priv-seguranca">
        <h2 id="priv-seguranca">Como os dados são protegidos</h2>
        <p>
          Senhas com hash, sessões que expiram, limite de tentativas de entrada, cada estúdio enxerga só os próprios clientes e as ações sensíveis (como redefinir uma senha) ficam
          registradas. Nenhum sistema é perfeito: se houver um incidente que afete seus dados, o responsável avisa e registra o que aconteceu.
        </p>
      </section>

      <p className="hint">Este texto é um apoio técnico do projeto e não substitui parecer jurídico.</p>
    </article>
  )
}
