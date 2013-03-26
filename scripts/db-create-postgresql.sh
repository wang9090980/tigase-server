#!/bin/bash

echo -e "\n\nusage: db-creaVte-mysql.sh tigase_username tigase_password database_name database_host \n\n"
 
if [ "${1}" = "-y" ] ; then
  NONINTERACTIVE=yes
  shift
fi

if [ -z "${1}" ] ; then
  echo "No username given. Using: tigase"
  USR_NAME=tigase
else
  USR_NAME="${1}"
fi

if [ -z "${2}" ] ; then
  echo "No password given. Using: tigase_passwd"
  USR_PASS=tigase_passwd
else
  USR_PASS="${2}"
fi

if [ -z "${3}" ] ; then
  echo "No DB name given. Using: tigase"
  DB_NAME=tigase
else
  DB_NAME="${3}"
fi


if [ -z "${4}" ] ; then
  echo "No DB hostname given. Using: localhost"
  DB_HOST=localhost
else
  DB_HOST="${4}"
fi


if [ -z "$NONINTERACTIVE" ] ; then
  echo ""
  echo "creating ${DB_NAME} database for user ${USR_NAME} identified by ${USR_PASS} password:"
  echo ""
 
  read -p "Press [Enter] key to start, otherwise abort..."
else
  echo "User: $USR_NAME, Pass: $USR_PASS, Db: $DB_NAME, Host: $DB_HOST"
fi

echo "Creating user"
su - postgres -c "createuser -d -S -R ${USR_NAME}"
su - postgres -c "psql -q -c \"ALTER USER ${USR_NAME} PASSWORD '${USR_PASS}';\""

echo "Creating database"
su - postgres -c "createdb -O ${USR_NAME} -E utf8 ${DB_NAME}"


echo "Loading DB schema"
su - postgres -c "cd /usr/share/tigase && psql -q -d ${DB_NAME} -f database/postgresql-schema-5-1.sql"

echo -e "\n\n\nconfiguration:\n\n--user-db=pgsql\n--user-db-uri=jdbc:postgresql://$DB_HOST/$DB_NAME?user=$USR_NAME&password=$USR_PASS&useUnicode=true&characterEncoding=UTF-8&autoCreateUser=true\n\n"
