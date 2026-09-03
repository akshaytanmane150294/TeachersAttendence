import psycopg2

conn = psycopg2.connect(host='localhost', port=5432, dbname='AttendenceSystem', user='postgres', password='1502')
cur = conn.cursor()
cur.execute("SELECT teacher_code, name_eng, mobile_no, username, password_hash FROM mst_teacher WHERE teacher_code LIKE '%1502%' OR name_eng ILIKE '%akshay%' LIMIT 10")
print(cur.fetchall())
cur.close()
conn.close()
