ALTER TABLE public.pacienti
    ADD CONSTRAINT uq_pacienti_cnp UNIQUE (cnp);