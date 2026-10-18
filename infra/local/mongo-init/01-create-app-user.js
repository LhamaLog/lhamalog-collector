// Executado pelo container do MongoDB apenas na primeira subida (volume vazio) - ADR_151.
// Cria o banco do collector e o usuario da aplicacao usados pelo perfil local.
// Para rodar de novo: docker compose -f infra/local/compose.yaml down -v (apaga os dados locais).
const dbName = process.env.MONGO_APP_DATABASE || 'lhamalog_collector';
const user = process.env.MONGO_APP_USERNAME || 'lhamalog';
const pwd = process.env.MONGO_APP_PASSWORD || 'lhamalog';

const appDb = db.getSiblingDB(dbName);
if (!appDb.getUser(user)) {
  appDb.createUser({ user: user, pwd: pwd, roles: [{ role: 'readWrite', db: dbName }] });
  print(`[lhamalog] usuario ${user} criado no banco ${dbName}`);
}
